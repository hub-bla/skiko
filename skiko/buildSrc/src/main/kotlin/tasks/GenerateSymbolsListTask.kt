package tasks

import OS
import Arch
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import tasks.symbols.SymbolType
import tasks.symbols.parseDumpbinSymbols
import tasks.symbols.parseNmPosix
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.readLines
import kotlin.io.path.writeText

internal fun generateDefFile(exportedTxt: Path, output: Path) {
    val symbols = exportedTxt.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    output.writeText(buildString {
        appendLine("EXPORTS")
        symbols.forEach { symbol ->
            appendLine("    $symbol")
        }
    })
}

internal fun generateVersionScript(symbolsTxt: Path, output: Path) {
    val symbols = symbolsTxt.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    output.writeText(buildString {
        appendLine("{")
        appendLine("  global:")
        symbols.forEach { symbol ->
            appendLine("    $symbol;")
        }
        appendLine("  local:")
        appendLine("    *;")
        appendLine("};")
    })
}

abstract class GenerateSymbolsListTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val targetOs: Property<OS>

    @get:Input
    abstract val targetArch: Property<Arch>

    @get:Optional
    @get:Input
    abstract val androidLlvmNm: Property<String>

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

        val coreExports = outDir.resolve("core_exports.txt")
        val extImports = outDir.resolve("ext_imports.txt")
        val symbolsFiltered = outDir.resolve("symbols_filtered.txt")
        val symbolsUnexported = outDir.resolve("symbols_unexported.txt")

        // 1. core exports
        val coreExportedList = extractSymbols(skiaLibs.files.toList() + coreObjectFiles.files.toList(), true)
        coreExports.writeText(coreExportedList.sorted().joinToString("\n"))

        // 2. all ext imports
        val extImportedList =
            extractSymbols(moduleObjectFiles.files.toList() + moduleLibs.files.toList(), false).toMutableList()

        // Keep JVM/JNI infrastructure globals
        extImportedList.addAll(coreExportedList.filter(::isJniInfrastructureSymbol))

        extImports.writeText(extImportedList.distinct().sorted().joinToString("\n"))

        // 3. initial keep list = intersection of ext imports + JNI with core exports
        val coreExportsSet = coreExportedList.toSet()
        val keepSet = extImportedList.filter { it in coreExportsSet }.toSet()
        symbolsFiltered.writeText(keepSet.sorted().joinToString("\n"))

        // 4. unexported = core exports minus what strip decided to keep
        val unexportedSet = coreExportsSet - keepSet
        symbolsUnexported.writeText(unexportedSet.sorted().joinToString("\n"))

        // Create export files for Linux or Windows. MacOS uses the raw txt file
        if (os.isLinux || os == OS.Android) {
            val versionScript = outDir.resolve("symbols.map")
            generateVersionScript(symbolsFiltered.toPath(), versionScript.toPath())
        }

        if (os.isWindows) {
            val defFile = outDir.resolve("symbols.def")
            generateDefFile(symbolsFiltered.toPath(), defFile.toPath())
        }

        logger.lifecycle("Symbols to keep: ${keepSet.size}, to hide: ${unexportedSet.size}")
    }

    private fun extractSymbols(files: List<File>, exported: Boolean): List<String> {
        val os = targetOs.get()
        val result = mutableSetOf<String>()
        if (files.isEmpty()) return emptyList()

        val executable = when (os) {
            OS.Windows -> "dumpbin"
            OS.Linux -> "nm"
            OS.MacOS -> "nm"
            OS.Android -> androidLlvmNm.get()
            OS.IOS, OS.TVOS, OS.Wasm ->
                throw IllegalStateException("generateSymbolsList is JVM-only and does not support ${os.name} target")
        }

        logger.lifecycle(
            "generateSymbolsList: extracting ${if (exported) "exported" else "undefined"} " +
                    "symbols using candidates $executable " +
                    "from ${files.size} files"
        )

        when {
            os.isMacOs || os.isLinux || os == OS.Android -> {
                val nmFlags = nmFlags(os, exported)
                val output = run(executable = executable, args = nmFlags, files = files)
                val wanted = if (exported) SymbolType.DefinedGlobal else SymbolType.Undefined
                parseNmPosix(output)
                    .filter { it.type == wanted }
                    .forEach { result.add(it.name) }
            }

            os.isWindows -> {
                val output = run(executable = executable, args = listOf("/SYMBOLS"), files = files)
                val wanted = if (exported) SymbolType.DefinedGlobal else SymbolType.Undefined
                parseDumpbinSymbols(output)
                    .filter { it.type == wanted }
                    .forEach { result.add(it.name) }
            }

            else -> throw IllegalStateException("generateSymbolsList is JVM-only and does not support ${os.name} target")
        }

        return result.toList()
    }

    private fun run(executable: String, args: List<String>, files: List<File>): String {
        val actualFiles = files.filter { it.isFile }
        if (actualFiles.isEmpty()) return ""

        val outStream = ByteArrayOutputStream()
        val errStream = ByteArrayOutputStream()

        val result = execOperations.exec {
            this.executable = executable
            this.args = args + actualFiles.map { it.absolutePath }
            this.standardOutput = outStream
            this.errorOutput = errStream
        }

        if (result.exitValue != 0) {
            error(
                """
                Command failed with exit code ${result.exitValue}
                stderr:
                $errStream
                """.trimIndent()
            )
        }

        return outStream.toString()
    }


    private fun nmFlags(os: OS, exported: Boolean): List<String> = when {
        !exported -> listOf("-P", "-u")
        os.isMacOs -> listOf("-P", "-g", "-U")
        else -> listOf("-P", "-g", "--defined-only")
    }

    private fun isJniInfrastructureSymbol(name: String): Boolean {
        val symbol = name.removePrefix("_")

        return symbol.startsWith("Java_") ||
                symbol.startsWith("JNI") ||
                symbol.startsWith("jvm")
    }

}
