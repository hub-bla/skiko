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
import tasks.symbols.SymbolType
import tasks.symbols.isJniInfrastructureSymbol
import tasks.symbols.parseDumpbinExports
import tasks.symbols.parseDumpbinSymbols
import tasks.symbols.parseNmPosix
import tasks.symbols.parseTbd
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
            appendLine("    \"$symbol\";")
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

    @get:InputFiles
    abstract val coreObjectFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val moduleObjectFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val skiaLibs: ConfigurableFileCollection

    @get:InputFiles
    abstract val moduleLibs: ConfigurableFileCollection

    @get:InputFiles
    @get:Optional
    abstract val systemLibs: ConfigurableFileCollection

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

        // Also keep JVM/JNI infrastructure globals. The single regex matcher works
        // on every JVM-host platform: Linux/Windows/Android (no leading underscore),
        // and macOS (leading `_` from the Mach-O ABI) — fixing the Windows-x64
        // miss in the previous Linux-vs-non-Linux branch.
        extImportedList.addAll(coreExportedList.filter(::isJniInfrastructureSymbol))

        extImports.writeText(extImportedList.distinct().sorted().joinToString("\n"))

        // 3. system lib exports (symbols from system DLLs/shared libs that must not be re-exported)
        // TODO: might be an overkill, but linux arm had issue with libexpat (XML parser) which is loaded as a system library
        // and bundled in skia as static archive as well
        val systemLibFiles = systemLibs.files.filter { it.isFile }
        val systemSymbolsSet: Set<String> = if (systemLibFiles.isNotEmpty()) {
            extractSystemLibSymbols(systemLibFiles).toSet().also {
                logger.lifecycle("generateSymbolsList: found ${it.size} exported symbols in ${systemLibFiles.size} system lib(s)")
            }
        } else {
            emptySet()
        }

        // 4. initial keep list = intersection of ext imports + JNI with core exports, minus system lib symbols
        val coreExportsSet = coreExportedList.toSet()
        val keepSet = extImportedList
            .filter { it in coreExportsSet }
            .filter { it !in systemSymbolsSet }
            .toSet()
        symbolsFiltered.writeText(keepSet.sorted().joinToString("\n"))

        // 5. unexported = core exports minus what strip decided to keep
        val unexportedSet = coreExportsSet - keepSet
        symbolsUnexported.writeText(unexportedSet.sorted().joinToString("\n"))

        if (os.isLinux) {
            val versionScript = outDir.resolve("symbols.map")
            generateVersionScript(symbolsFiltered.toPath(), versionScript.toPath())
        }

        if (os.isWindows) {
            val defFile = outDir.resolve("symbols.def")
            generateDefFile(symbolsFiltered.toPath(), defFile.toPath())
        }

        logger.lifecycle("Symbols to keep: ${keepSet.size}, to hide: ${unexportedSet.size}")
    }

    /**
     * Extracts exported symbol names from system shared libraries / import libs.
     * Uses `dumpbin /EXPORTS` on Windows (import libs), and `nm -g` on Linux/macOS (shared objects).
     */
    private fun extractSystemLibSymbols(files: List<File>): List<String> {
        val os = targetOs.get()
        val arch = targetArch.get()
        val result = mutableSetOf<String>()
        if (files.isEmpty()) return emptyList()

        val executableCandidates = resolveExecutableCandidates(os, arch)

        when {
            os.isMacOs || os.isLinux -> {
                // Split files into .tbd stubs (macOS SDK) and regular shared objects.
                val (tbdFiles, soFiles) = files.partition { it.extension == "tbd" }

                // Parse .tbd (Text-Based Dylib) stubs — YAML-like SDK files that list every
                // linker-visible symbol exported by macOS frameworks. The parser handles every
                // symbol-bearing key (symbols, weak-symbols, re-exports, objc-classes, …).
                tbdFiles.forEach { tbd ->
                    parseTbd(tbd.readText()).forEach { result.add(it) }
                }

                // Regular shared objects — use nm with -D (dynamic symbol table).
                // Shared libraries export symbols via the dynamic symbol table (.dynsym), not
                // the regular symbol table (.symtab).  nm --defined-only without -D produces
                // zero results for stripped .so files; -D reads the right table.
                if (soFiles.isNotEmpty()) {
                    val nmFlags = nmFlags(os, exported = true, dynamic = os.isLinux)
                    try {
                        val output = run(executables = executableCandidates, args = nmFlags, files = soFiles)
                        parseNmPosix(output)
                            .filter { it.type == SymbolType.DefinedGlobal }
                            .forEach { result.add(it.name) }
                    } catch (t: Throwable) {
                        logger.warn("generateSymbolsList: could not extract system lib symbols via nm: ${t.message}")
                    }
                }
            }
            else -> {
                // On Windows, use dumpbin /EXPORTS to read the export table of each import lib.
                files.forEach { file ->
                    try {
                        val output = run(executables = executableCandidates, args = listOf("/EXPORTS"), files = listOf(file))
                        parseDumpbinExports(output).forEach { result.add(it) }
                    } catch (t: Throwable) {
                        logger.warn("generateSymbolsList: could not extract exports from ${file.name}: ${t.message}")
                    }
                }
            }
        }

        return result.toList()
    }

    private fun extractSymbols(files: List<File>, exported: Boolean): List<String> {
        val os = targetOs.get()
        val arch = targetArch.get()
        val result = mutableSetOf<String>()
        if (files.isEmpty()) return emptyList()

        val executableCandidates = resolveExecutableCandidates(os, arch)
        logger.lifecycle(
            "generateSymbolsList: extracting ${if (exported) "exported" else "undefined"} symbols using candidates ${executableCandidates} from ${files.size} files"
        )

        when {
            os.isMacOs || os.isLinux -> {
                val nmFlags = nmFlags(os, exported)
                val output = run(executables = executableCandidates, args = nmFlags, files = files)
                val wanted = if (exported) SymbolType.DefinedGlobal else SymbolType.Undefined
                parseNmPosix(output)
                    .filter { it.type == wanted }
                    .forEach { result.add(it.name) }
            }
            else -> {
                val output = run(executables = executableCandidates, args = listOf("/SYMBOLS"), files = files)
                val wanted = if (exported) SymbolType.DefinedGlobal else SymbolType.Undefined
                parseDumpbinSymbols(output)
                    .filter { it.type == wanted }
                    .forEach { result.add(it.name) }
            }
        }

        return result.toList()
    }

    private fun run(executables: List<String>, args: List<String>, files: List<File>): String {
        var activeExecutableIndex = 0
        var executable = executables[activeExecutableIndex]

        // Filter out directories immediately to prevent command failures
        val actualFiles = files.filter { it.isFile }

        while (true) {
            val combinedOutput = StringBuilder()
            val errorOutputBuilder = StringBuilder()
            var batchFailed = false
            var thrownException: Throwable? = null

            try {
                actualFiles.chunked(100).forEachIndexed { index, batch ->
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
            } catch (t: Throwable) {
                batchFailed = true
                thrownException = t
            }

            if (!batchFailed) {
                return combinedOutput.toString()
            }

            if (activeExecutableIndex < executables.lastIndex) {
                val previous = executable
                activeExecutableIndex += 1
                executable = executables[activeExecutableIndex]
                logger.warn("generateSymbolsList: failed with '$previous'; retrying with '$executable'. Error was: ${thrownException?.message}")
                continue
            }

            val firstFile = actualFiles.firstOrNull()?.absolutePath.orEmpty()
            logger.error(
                "generateSymbolsList: FATAL. Exhausted all executables. Last attempt failed to run '$executable'. \n" +
                        "Args=$args\n" +
                        "FirstFile=$firstFile\n" +
                        "Stderr Output=${errorOutputBuilder}\n" +
                        "PATH=${System.getenv("PATH")}",
                thrownException
            )
            throw thrownException ?: RuntimeException("Execution failed without an exception")
        }
    }


    private fun nmFlags(os: OS, exported: Boolean, dynamic: Boolean = false): List<String> {
        // Always use POSIX format (`-P`) so output is column-stable across
        // GNU binutils nm, LLVM nm, BSD/macOS nm, and aarch64-linux-gnu-nm.
        return when {
            !exported -> listOf("-P", "-u")
            os.isMacOs -> listOf("-P", "-g", "-U")
            dynamic -> listOf("-P", "-D", "--defined-only")  // shared objects: use dynamic symbol table
            else -> listOf("-P", "-g", "--defined-only")
        }
    }

    private fun executableCandidates(os: OS, arch: Arch): List<String> = when (os) {
        OS.Windows -> listOf("dumpbin")
        OS.Linux -> when {
            arch == Arch.Arm64 && hostArch != Arch.Arm64 -> listOf("aarch64-linux-gnu-nm", "nm")
            else -> listOf("nm")
        }
        OS.MacOS -> listOf("nm")
        OS.Android -> listOf("llvm-nm", "nm")
        OS.IOS, OS.TVOS, OS.Wasm -> throw IllegalStateException("generateSymbolsList is JVM-only and does not support ${os.name} targets")
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
