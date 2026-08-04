import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

private val binaryExtensions = setOf("so", "dll", "dylib", "wasm", "mjs", "js")

abstract class BinarySizeReportTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val binaryFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @get:Input
    abstract val module: Property<String>

    @get:Input
    abstract val inputKind: Property<BinarySizeInputKind>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun report() {
        val files = binaryFiles.files.sortedBy { it.toPath().toString() }
        val rows = when (inputKind.get()) {
            BinarySizeInputKind.ARCHIVE -> files.flatMap(::archiveRows)
            BinarySizeInputKind.EXECUTABLE -> files.map(::executableRow)
        }
        require(rows.isNotEmpty()) { "No binary payloads found for ${module.get()}" }
        writeTsv(reportFile.get().asFile, rows)
    }

    private fun archiveRows(archive: File): List<BinarySizeRow> {
        require(archive.isFile) { "Archive not found: $archive" }
        val archiveTarget = binaryTargetOrNull(archive.name) ?: return emptyList()
        return ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    val entryTarget = binaryTargetOrNull(entry.name)
                    entry.name.binaryExtension() in binaryExtensions &&
                        // Web binaries may not identify their target in the file name.
                        (entryTarget == null || entryTarget == archiveTarget)
                }
                .map { entry ->
                    require(entry.size >= 0) { "Unknown size for ${archive.path}: ${entry.name}" }
                    BinarySizeRow(archiveTarget.platform, archiveTarget.arch, module.get(), entry.name, entry.size)
                }
                .toList()
        }
    }

    private fun executableRow(executable: File): BinarySizeRow {
        require(executable.isFile) { "Executable not found: $executable" }
        val target = binaryTarget(executable.path)
        return BinarySizeRow(target.platform, target.arch, module.get(), executable.name, executable.length())
    }
}

abstract class AggregateBinarySizeReportsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reportsDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun aggregate() {
        val fragments = reportsDirectory.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "tsv" }
            .toList()
        require(fragments.isNotEmpty()) { "No binary size report fragments found" }
        val rows = fragments.flatMap { fragment -> fragment.readLines().map { parseRow(it, fragment) } }
        require(rows.isNotEmpty()) { "No binary size rows found" }
        writeMarkdown(reportFile.get().asFile, rows)
    }
}

fun Project.configureBinarySizeReports() {
    val archivePattern = when {
        supportAndroid -> "libs/*android*.jar"
        supportWeb -> "libs/*wasm*.jar"
        supportAwt -> "libs/*${hostOs.id}*.jar"
        else -> "libs/*.jar"
    }
    tasks.register<BinarySizeReportTask>("reportArchiveBinarySizes") {
        module.set(project.name)
        inputKind.set(BinarySizeInputKind.ARCHIVE)
        binaryFiles.from(layout.buildDirectory.map { directory ->
            directory.asFileTree.matching { include(archivePattern) }
        })
        reportFile.set(layout.buildDirectory.file("reports/binary-sizes/archives.tsv"))
    }
    tasks.register<BinarySizeReportTask>("reportKexeBinarySizes") {
        module.set(project.name)
        inputKind.set(BinarySizeInputKind.EXECUTABLE)
        binaryFiles.from(layout.buildDirectory.map { directory ->
            directory.asFileTree.matching { include("bin/**/debugTest/test.kexe") }
        })
        reportFile.set(layout.buildDirectory.file("reports/binary-sizes/executables.tsv"))
    }
}

fun Project.configureBinarySizeReportAggregation() {
    tasks.register<AggregateBinarySizeReportsTask>("aggregateBinarySizeReports") {
        val reportsPath = providers.gradleProperty("binarySizeReportsDir")
            .orElse("binary-size-reports")
        reportsDirectory.set(layout.projectDirectory.dir(reportsPath))
        reportFile.set(layout.buildDirectory.file("reports/binary-sizes/combined.md"))
    }
}

private fun parseRow(line: String, source: File): BinarySizeRow {
    val fields = line.split('\t')
    require(fields.size == 5 && fields.all(String::isNotEmpty)) { "Malformed row in $source: $line" }
    val size = fields[4].toLongOrNull()
    require(size != null && size >= 0) { "Invalid size in $source: $line" }
    return BinarySizeRow(fields[0], fields[1], fields[2], fields[3], size)
}

private fun writeTsv(report: File, rows: List<BinarySizeRow>) {
    report.parentFile.mkdirs()
    report.bufferedWriter().use { writer ->
        rows.sortedWith(compareBy(BinarySizeRow::platform, BinarySizeRow::arch, BinarySizeRow::file)).forEach { row ->
            writer.appendLine(listOf(row.platform, row.arch, row.module, row.file, row.size).joinToString("\t"))
        }
    }
}

private fun writeMarkdown(report: File, rows: List<BinarySizeRow>) {
    report.parentFile.mkdirs()
    report.bufferedWriter().use { writer ->
        writer.appendLine("### Combined binary sizes")
        rows.groupBy(BinarySizeRow::module).toSortedMap().forEach { (module, moduleRows) ->
            writer.appendLine()
            writer.appendLine("#### `$module`")
            writer.appendLine()
            writer.appendLine("| Platform | Arch | File | Size |")
            writer.appendLine("|---|---|---|---:|")
            moduleRows.sortedWith(compareBy(BinarySizeRow::platform, BinarySizeRow::arch, BinarySizeRow::file)).forEach { row ->
                writer.appendLine(
                    "| ${row.platform} | ${row.arch} | `${row.file}` | ${humanSize(row.size)} (${row.size} bytes) |"
                )
            }
        }
    }
}

private fun binaryTarget(identity: String): BinaryTarget = binaryTargetOrNull(identity)
    ?: error("Unknown binary target: $identity")

private fun binaryTargetOrNull(identity: String): BinaryTarget? = binaryTargets.firstOrNull { it.matches(identity) }

private fun String.binaryExtension(): String = substringAfterLast('.', "").lowercase()

private fun humanSize(size: Long): String = when {
    size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", size.toDouble() / (1024 * 1024))
    size >= 1024 -> String.format(Locale.US, "%.1f KiB", size.toDouble() / 1024)
    else -> "$size B"
}

private data class BinaryTarget(val marker: String, val platform: String, val arch: String) {
    fun matches(value: String): Boolean = value.contains(marker, ignoreCase = true)
}

private val binaryTargets = listOf(
    BinaryTarget("android-arm64", "Android", "arm64"),
    BinaryTarget("android-x64", "Android", "x64"),
    BinaryTarget("linux-arm64", "Linux", "arm64"),
    BinaryTarget("linux-x64", "Linux", "x64"),
    BinaryTarget("macos-arm64", "macOS", "arm64"),
    BinaryTarget("macos-x64", "macOS", "x64"),
    BinaryTarget("windows-arm64", "Windows", "arm64"),
    BinaryTarget("windows-x64", "Windows", "x64"),
    BinaryTarget("iosSimulatorArm64", "iOS", "arm64"),
    BinaryTarget("iosArm64", "iOS", "arm64"),
    BinaryTarget("iosX64", "iOS", "x64"),
    BinaryTarget("macosArm64", "macOS", "arm64"),
    BinaryTarget("macosX64", "macOS", "x64"),
    BinaryTarget("linuxArm64", "Linux", "arm64"),
    BinaryTarget("linuxX64", "Linux", "x64"),
    BinaryTarget("skiko-wasm", "Web", "wasm"),
)

enum class BinarySizeInputKind { ARCHIVE, EXECUTABLE }

private data class BinarySizeRow(
    val platform: String,
    val arch: String,
    val module: String,
    val file: String,
    val size: Long,
)
