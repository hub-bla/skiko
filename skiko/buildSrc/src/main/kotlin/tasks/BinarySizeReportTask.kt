import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

abstract class BinarySizeReportTask : DefaultTask() {
    private val configuredInputs = mutableListOf<ConfiguredBinarySizeInput>()

    init {
        outputs.upToDateWhen { false }
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val binaryFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @get:Input
    val binaryMetadata: List<String>
        get() = configuredInputs.map {
            "${it.kind}|${it.os}|${it.arch}|${it.source}|${it.fileName}|${it.payloadFileName}"
        }

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Optional
    @get:OutputDirectory
    abstract val stageDirectory: DirectoryProperty

    fun archive(file: Provider<File>, os: OS, arch: Arch, source: String, payloadFileName: String = "") {
        addInput(file, BinarySizeInputKind.ARCHIVE, os, arch, source, payloadFileName = payloadFileName)
    }

    fun executable(file: Provider<File>, os: OS, arch: Arch, source: String, fileName: String) {
        addInput(file, BinarySizeInputKind.EXECUTABLE, os, arch, source, fileName)
    }

    private fun addInput(
        file: Provider<File>,
        kind: BinarySizeInputKind,
        os: OS,
        arch: Arch,
        source: String,
        fileName: String = "",
        payloadFileName: String = "",
    ) {
        configuredInputs += ConfiguredBinarySizeInput(file, kind, os, arch, source, fileName, payloadFileName)
        binaryFiles.from(file)
    }

    @TaskAction
    fun report() {
        val inputs = configuredInputs.mapNotNull { input ->
            input.file.orNull?.takeIf(File::isFile)?.let { file ->
                BinarySizeInput(
                    file,
                    input.kind,
                    input.os,
                    input.arch,
                    input.source,
                    input.fileName.ifEmpty { file.name },
                    input.payloadFileName,
                )
            }
        }
        BinarySizeReporter.report(inputs, reportFile.get().asFile, stageDirectory.orNull?.asFile)
    }
}

fun SkikoProjectContext.configureBinarySizeReports() {
    val stagePath = project.providers.gradleProperty("binarySizeStageDir")
    val archiveReport = project.tasks.register<BinarySizeReportTask>("reportArchiveBinarySizes") {
        reportFile.set(project.layout.buildDirectory.file("reports/binary-sizes/archives.md"))
        if (stagePath.isPresent) {
            stageDirectory.set(project.layout.buildDirectory.dir(stagePath.map { "$it/archives" }))
        }
    }
    val executableReport = project.tasks.register<BinarySizeReportTask>("reportKexeBinarySizes") {
        reportFile.set(project.layout.buildDirectory.file("reports/binary-sizes/executables.md"))
        if (stagePath.isPresent) {
            stageDirectory.set(project.layout.buildDirectory.dir(stagePath.map { "$it/executables" }))
        }
    }
    archiveReport.configure {
        allJvmRuntimeJars.forEach { (target, jarTask) ->
            val (os, arch) = target
            val prefix = if (os.isWindows) artifacts.artifactIdPrefix else "lib${artifacts.artifactIdPrefix}"
            archive(
                jarTask.flatMap { it.archiveFile }.map { it.asFile },
                os,
                arch,
                artifacts.jvmRuntimeArtifactIdFor(os, arch),
                "$prefix-${os.id}-${arch.id}${os.dynamicLibExt}",
            )
        }
        additionalRuntimeLibraries.forEach { library ->
            archive(
                library.jarTask.flatMap { it.archiveFile }.map { it.asFile },
                library.targetOs,
                library.targetArch,
                library.artifactId,
            )
        }
        wasmRuntimeJar?.let { jarTask ->
            archive(
                jarTask.flatMap { it.archiveFile }.map { it.asFile },
                OS.Wasm,
                Arch.Wasm,
                artifacts.wasmArtifactId,
            )
        }
    }
    executableReport.configure {
        nativeTargets.forEach { configuredTarget ->
            val binary = configuredTarget.target.binaries.getTest(NativeBuildType.DEBUG).outputFile
            executable(
                configuredTarget.target.binaries.getTest(NativeBuildType.DEBUG).linkTaskProvider.map { binary },
                configuredTarget.os,
                configuredTarget.arch,
                "${artifacts.artifactIdPrefix} ${configuredTarget.target.name} debugTest",
                "${configuredTarget.target.name}/debugTest/${binary.name}",
            )
        }
    }
}

object BinarySizeReporter {
    private val binaryExtensions = setOf("so", "dll", "dylib", "wasm", "mjs", "js")

    fun report(inputs: List<BinarySizeInput>, report: File, stageDirectory: File?) {
        val rows = mutableListOf<BinarySizeRow>()
        inputs.forEach { input ->
            when (input.kind) {
                BinarySizeInputKind.ARCHIVE -> ZipFile(input.file).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter {
                            !it.isDirectory &&
                                it.name.substringAfterLast('.', "").lowercase() in binaryExtensions &&
                                (input.payloadFileName.isEmpty() ||
                                    it.name.substringAfterLast('/') == input.payloadFileName)
                        }
                        .sortedBy { it.name }
                        .toList()
                    if (entries.isEmpty()) return@use
                    rows += BinarySizeRow(
                        platform = input.os.displayName,
                        arch = input.arch.id,
                        source = input.source,
                        file = input.file.name,
                        size = input.file.length(),
                        sha256 = sha256(input.file),
                    )
                    entries.forEach { entry ->
                        val content = zip.getInputStream(entry).use { it.readBytes() }
                        rows += BinarySizeRow(
                            platform = input.os.displayName,
                            arch = input.arch.id,
                            source = input.source,
                            file = entry.name,
                            size = content.size.toLong(),
                            sha256 = sha256(content),
                        )
                        stageDirectory?.let { stage ->
                            val destination = safeResolve(
                                stage.resolve("${input.os.id}/${input.arch.id}/${input.source}/${input.file.name}"),
                                entry.name,
                            )
                            destination.parentFile.mkdirs()
                            destination.writeBytes(content)
                        }
                    }
                }
                BinarySizeInputKind.EXECUTABLE -> {
                    stageDirectory?.let { stage ->
                        val destination = stage.resolve("${input.os.id}/${input.arch.id}/${input.source}/${input.fileName}")
                        destination.parentFile.mkdirs()
                        input.file.copyTo(destination, overwrite = true)
                    }
                    rows += BinarySizeRow(
                        platform = input.os.displayName,
                        arch = input.arch.id,
                        source = input.source,
                        file = input.fileName,
                        size = input.file.length(),
                        sha256 = sha256(input.file),
                    )
                }
            }
        }
        require(rows.isNotEmpty()) {
            "No binary payloads found in configured inputs"
        }
        writeReport("Skiko binary sizes", rows, report)
    }

    private fun writeReport(title: String, rows: List<BinarySizeRow>, report: File) {
        report.parentFile.mkdirs()
        report.bufferedWriter().use { writer ->
            writer.appendLine("### $title")
            writer.appendLine()
            writer.appendLine("| Platform | Arch | Source | File | Size | SHA256 |")
            writer.appendLine("|---|---|---|---|---:|---|")
            rows.sortedWith(compareBy(BinarySizeRow::platform, BinarySizeRow::source, BinarySizeRow::file))
                .forEach { row ->
                    writer.appendLine(
                        "| ${escape(row.platform)} | ${escape(row.arch)} | ${escape(row.source)} | " +
                            "`${escape(row.file)}` | ${humanSize(row.size)} (${row.size} bytes) | `${row.sha256}` |"
                    )
                }
        }
    }

    private fun humanSize(size: Long): String = when {
        size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", size.toDouble() / (1024 * 1024))
        size >= 1024 -> String.format(Locale.US, "%.1f KiB", size.toDouble() / 1024)
        else -> "$size B"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex().take(12)
    }

    private fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).toHex().take(12)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun safeResolve(directory: File, entryName: String): File {
        val normalizedName = entryName.replace('\\', '/')
        val parts = normalizedName.split('/')
        require(
            !normalizedName.startsWith('/') &&
                !parts.first().matches(Regex("[A-Za-z]:")) &&
                parts.none { it.isEmpty() || it == "." || it == ".." }
        ) { "Unsafe archive entry: $entryName" }
        val destination = parts.fold(directory) { current, part -> current.resolve(part) }
        require(destination.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) {
            "Unsafe archive entry: $entryName"
        }
        return destination
    }

    private fun escape(value: String): String = value.replace("|", "\\|")
}

private val OS.displayName: String
    get() = when (this) {
        OS.MacOS -> "macOS"
        OS.IOS -> "iOS"
        OS.TVOS -> "tvOS"
        OS.Wasm -> "Web"
        else -> id.replaceFirstChar(Char::uppercase)
    }

enum class BinarySizeInputKind { ARCHIVE, EXECUTABLE }

private data class ConfiguredBinarySizeInput(
    val file: Provider<File>,
    val kind: BinarySizeInputKind,
    val os: OS,
    val arch: Arch,
    val source: String,
    val fileName: String,
    val payloadFileName: String,
)

data class BinarySizeInput(
    val file: File,
    val kind: BinarySizeInputKind,
    val os: OS,
    val arch: Arch,
    val source: String,
    val fileName: String = file.name,
    val payloadFileName: String = "",
)

private data class BinarySizeRow(
    val platform: String,
    val arch: String,
    val source: String,
    val file: String,
    val size: Long,
    val sha256: String,
)