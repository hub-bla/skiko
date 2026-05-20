package tasks

import OS
import Arch
import hostArch
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import tasks.symbols.SymbolType
import tasks.symbols.isJniInfrastructureSymbol
import tasks.symbols.parseDumpbinSymbols
import tasks.symbols.parseNmPosix
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Computes the exported ABI for a JVM extension module.
 *
 * Extension shared libraries should normally expose only their JNI entry points.
 * Skia/SKSG/Ganesh symbols are implementation details and should stay local to
 * the extension, even when the linked static archives were compiled with default
 * visibility for experimentation.
 */
abstract class GenerateExtensionExportsListTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val targetOs: Property<OS>

    @get:Input
    abstract val targetArch: Property<Arch>

    @get:InputFiles
    abstract val objectFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val os = targetOs.get()
        val arch = targetArch.get()
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val defined = extractDefinedSymbols(objectFiles.files.toList(), os, arch)
        val exported = defined
            .filter(::isJniInfrastructureSymbol)
            .sorted()

        val exportedSymbols = outDir.resolve("symbols_filtered.txt")
        exportedSymbols.writeText(exported.joinToString("\n"))

        when {
            os.isLinux || os == OS.Android -> {
                generateVersionScript(exportedSymbols.toPath(), outDir.resolve("symbols.map").toPath())
            }
            os.isWindows -> {
                generateDefFile(exportedSymbols.toPath(), outDir.resolve("symbols.def").toPath())
            }
        }

        logger.lifecycle(
            "generateExtensionExportsList: targetOs=${os.name}, targetArch=${arch.name}, " +
                    "defined=${defined.size}, exported=${exported.size}"
        )
    }

    private fun extractDefinedSymbols(files: List<File>, os: OS, arch: Arch): List<String> {
        val actualFiles = files.filter { it.isFile }
        if (actualFiles.isEmpty()) return emptyList()

        val output = when {
            os.isMacOs || os.isLinux || os == OS.Android -> {
                run(executableCandidates(os, arch), listOf("-P", "-g", "--defined-only"), actualFiles)
            }
            os.isWindows -> {
                run(executableCandidates(os, arch), listOf("/SYMBOLS"), actualFiles)
            }
            else -> error("GenerateExtensionExportsListTask does not support ${os.name}")
        }

        val parsed = if (os.isWindows) parseDumpbinSymbols(output) else parseNmPosix(output)
        return parsed
            .filter { it.type == SymbolType.DefinedGlobal }
            .map { it.name }
            .distinct()
            .toList()
    }

    private fun run(executables: List<String>, args: List<String>, files: List<File>): String {
        var activeExecutableIndex = 0
        var executable = executables[activeExecutableIndex]

        while (true) {
            val combinedOutput = StringBuilder()
            val errorOutputBuilder = StringBuilder()
            var thrownException: Throwable? = null

            try {
                files.chunked(100).forEach { batch ->
                    val outStream = ByteArrayOutputStream()
                    val errStream = ByteArrayOutputStream()
                    execOperations.exec {
                        this.executable = executable
                        this.args = args + batch.map { it.absolutePath }
                        this.standardOutput = outStream
                        this.errorOutput = errStream
                    }
                    combinedOutput.append(outStream.toString())
                    errorOutputBuilder.append(errStream.toString())
                }
                return combinedOutput.toString()
            } catch (t: Throwable) {
                thrownException = t
            }

            if (activeExecutableIndex < executables.lastIndex) {
                val previous = executable
                activeExecutableIndex += 1
                executable = executables[activeExecutableIndex]
                logger.warn("generateExtensionExportsList: failed with '$previous'; retrying with '$executable'. Error was: ${thrownException?.message}")
                continue
            }

            logger.error(
                "generateExtensionExportsList: FATAL. Exhausted all executables. Last attempt failed to run '$executable'.\n" +
                        "Args=$args\n" +
                        "FirstFile=${files.firstOrNull()?.absolutePath.orEmpty()}\n" +
                        "Stderr Output=$errorOutputBuilder\n" +
                        "PATH=${System.getenv("PATH")}",
                thrownException
            )
            throw thrownException ?: RuntimeException("Execution failed without an exception")
        }
    }

    private fun executableCandidates(os: OS, arch: Arch): List<String> = when {
        os.isWindows -> listOf("dumpbin")
        os.isLinux && arch == Arch.Arm64 && hostArch != Arch.Arm64 -> listOf("aarch64-linux-gnu-nm", "nm")
        os.isLinux -> listOf("nm")
        os.isMacOs -> listOf("nm")
        os == OS.Android -> listOf("llvm-nm", "nm")
        else -> error("GenerateExtensionExportsListTask does not support ${os.name}")
    }
}
