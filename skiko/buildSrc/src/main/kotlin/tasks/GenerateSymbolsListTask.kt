package tasks

import OS
import Arch
import hostArch
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import tasks.configuration.generateDefFile
import tasks.configuration.generateVersionScript
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.inject.Inject

abstract class GenerateSymbolsListTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val targetOs: Property<OS>

    @get:Input
    abstract val targetArch: Property<Arch>

    @get:InputFiles
    abstract val coreObjectFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val moduleObjectFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val skiaLibs: ConfigurableFileCollection

    @get:InputFiles
    abstract val moduleLibs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val os = targetOs.get()
        val arch = targetArch.get()
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        logger.lifecycle(
            "generateSymbolsList: targetOs=${os.name}, targetArch=${arch.name}, coreObjects=${coreObjectFiles.files.size}, moduleObjects=${moduleObjectFiles.files.size}, skiaLibs=${skiaLibs.files.size}, moduleLibs=${moduleLibs.files.size}"
        )

        val coreExports       = outDir.resolve("core_exports.txt")
        val extImports        = outDir.resolve("ext_imports.txt")
        val symbolsFiltered   = outDir.resolve("symbols_filtered.txt")
        val symbolsUnexported = outDir.resolve("symbols_unexported.txt")

        // 1. core exports
        val coreExportedList = extractSymbols(skiaLibs.files.toList() + coreObjectFiles.files.toList(), true)
        coreExports.writeText(coreExportedList.sorted().joinToString("\n"))

        // 2. all ext imports
        val extImportedList = extractSymbols(moduleObjectFiles.files.toList() + moduleLibs.files.toList(), false).toMutableList()

        // also keep jvm infrastructure globals
        extImportedList.addAll(coreExportedList.filter {
            it.contains("_jvm") || it.contains("_JNI") || it.contains("_Java_")
        })

        extImports.writeText(extImportedList.distinct().sorted().joinToString("\n"))

        // 4. initial keep list = intersection of ext imports + JNI with core exports
        val coreExportsSet = coreExportedList.toSet()
        val keepSet = extImportedList.filter { it in coreExportsSet }.toSet()
        symbolsFiltered.writeText(keepSet.sorted().joinToString("\n"))

        // 5. unexported = core exports minus what strip decided to keep
        val unexportedSet = coreExportsSet - keepSet
        symbolsUnexported.writeText(unexportedSet.sorted().joinToString("\n"))

        if (os.isLinux) {
            val versionScript = outDir.resolve("symbols.map")
            generateVersionScript(symbolsUnexported.toPath(), versionScript.toPath())
        }

        if (os.isWindows) {
            val defFile = outDir.resolve("symbols.def")
            generateDefFile(symbolsFiltered.toPath(), defFile.toPath())
        }

        logger.lifecycle("Symbols to keep: ${keepSet.size}, to hide: ${unexportedSet.size}")
    }

    private fun extractSymbols(files: List<File>, exported: Boolean): List<String> {
        val os = targetOs.get()
        val arch = targetArch.get()
        val result = mutableSetOf<String>()
        if (files.isEmpty()) return emptyList()

        val executableCandidates = resolveExecutableCandidates(os, arch)
        logger.lifecycle(
            "generateSymbolsList: extracting ${if (exported) "exported" else "undefined"} symbols with '${executableCandidates.first()}' from ${files.size} files"
        )

        when {
            os.isMacOs || os.isLinux -> {
                val nmFlags = nmFlags(os, exported)
                run(executables = executableCandidates, args = nmFlags, files = files).lines().forEach { line ->
                    val s = line.trim().split(" ").lastOrNull().orEmpty()
                    if (s.isNotEmpty() && !s.contains(":") && !s.startsWith("/")) {
                        result.add(s)
                    }
                }
            }
            else -> {
                run(executables = executableCandidates, args = listOf("/SYMBOLS"), files = files).lines().forEach { line ->
                    if (line.contains("External")) {
                        val isUndef = line.contains("UNDEF")
                        if (exported && !isUndef || !exported && isUndef) {
                            val s = line.substringAfter("|").trim().substringBefore(" ")
                            if (s.isNotEmpty() && !s.startsWith("__imp_") && !s.startsWith(".refptr") && !s.startsWith("__real@") && !s.startsWith("__xmm@")) {
                                result.add(s)
                            }
                        }
                    }
                }
            }
        }

        return result.toList()
    }

    private fun run(executables: List<String>, args: List<String>, files: List<File>): String {
        var activeExecutableIndex = 0
        var executable = executables[activeExecutableIndex]
        while (true) {
            val out = ByteArrayOutputStream()
            val command = buildCommand(executable = executable, args = args, files = files)
            logger.lifecycle("generateSymbolsList: command: $command")
            try {
                execOperations.exec {
                    this.executable = executable
                    this.args = args + files.map { it.absolutePath }
                    standardOutput = out
                }
                return out.toString()
            } catch (t: Throwable) {
                if (canRetryWithNextExecutable(t, executables, activeExecutableIndex)) {
                    val previous = executable
                    activeExecutableIndex += 1
                    executable = executables[activeExecutableIndex]
                    logger.warn("generateSymbolsList: failed to start '$previous'; retrying with '$executable'")
                    continue
                }

                val firstFile = files.firstOrNull()?.absolutePath.orEmpty()
                logger.error(
                    "generateSymbolsList: failed to start '$executable'. args=$args, firstFile=$firstFile, PATH=${System.getenv("PATH")}",
                    t
                )
                throw t
            }
        }
    }

    private fun buildCommand(executable: String, args: List<String>, files: List<File>): String {
        return (listOf(executable) + args + files.map { it.absolutePath }).joinToString(" ")
    }

    private fun canRetryWithNextExecutable(
        throwable: Throwable,
        executables: List<String>,
        activeExecutableIndex: Int
    ): Boolean {
        if (activeExecutableIndex >= executables.lastIndex) return false
        return throwable.message?.contains("starting process") == true
    }

    private fun nmFlags(os: OS, exported: Boolean): List<String> {
        return when {
            !exported -> listOf("-u")
            os.isMacOs -> listOf("-g", "-U")
            else -> listOf("-g", "--defined-only")
        }
    }

    private fun executableCandidates(os: OS, arch: Arch): List<String> = when (os) {
        OS.Windows -> listOf("dumpbin")
        OS.Linux -> when {
            arch == Arch.Arm64 && hostArch != Arch.Arm64 -> listOf("aarch64-linux-gnu-nm", "nm")
            else -> listOf("nm")
        }

        OS.MacOS, OS.Android -> listOf("nm")
        OS.IOS, OS.TVOS -> throw IllegalStateException("generateSymbolsList is JVM-only and does not support ${os.name} targets")
        OS.Wasm -> throw IllegalStateException("generateSymbolsList is JVM-only and does not support wasm targets")
    }

    private fun resolveExecutableCandidates(os: OS, arch: Arch): List<String> {
        return executableCandidates(os, arch)
            .map { candidate -> findExecutableInPath(candidate) ?: candidate }
            .distinct()
    }

    private fun findExecutableInPath(name: String): String? {
        val pathValue = System.getenv("PATH").orEmpty()
        val executableNames = if (name.endsWith(".exe")) listOf(name) else listOf(name, "$name.exe")
        return pathValue
            .split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { dir -> executableNames.asSequence().map { execName -> Paths.get(dir, execName) } }
            .firstOrNull { candidate -> isExecutableFile(candidate) }
            ?.toAbsolutePath()
            ?.toString()
    }

    private fun isExecutableFile(path: Path): Boolean {
        return Files.isRegularFile(path) && Files.isExecutable(path)
    }
}
