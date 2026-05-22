import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Generates an "unexported symbols" list file containing all globally-defined
 * symbols from the Skia static libraries. This file is intended to be passed
 * to the Apple linker via `-unexported_symbols_list` so that Skia internals
 * are not exported from the final binary.
 *
 * This is used on macOS native targets where symbol *renaming* (as done by
 * [PatchSkiaSymbolsTask] for iOS/tvOS) is not needed, but we still want to
 * prevent Skia internals from appearing in the exported symbol table.
 *
 * The Skia static libraries themselves are **not** modified — the linker
 * resolves all symbols normally during linking and then strips exports
 * based on the generated list.
 */
abstract class HideSkiaSymbolsTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    /** Paths to Skia static libraries (.a) whose symbols should be unexported. */
    @get:InputFiles
    abstract val skiaLibs: ListProperty<File>

    /** The generated unexported-symbols list file for the Apple linker. */
    @get:OutputFile
    abstract val unexportedSymbolsList: RegularFileProperty

    @TaskAction
    fun execute() {
        val allLibs = skiaLibs.get()
        val allSymbols = mutableSetOf<String>()

        for (lib in allLibs) {
            val symbols = extractGlobalDefinedSymbols(lib)
            logger.lifecycle("  ${lib.name}: ${symbols.size} global defined symbols")
            allSymbols.addAll(symbols)
        }

        val outFile = unexportedSymbolsList.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(allSymbols.sorted().joinToString("\n") + "\n")
        logger.lifecycle("Written ${allSymbols.size} symbols to $outFile")
    }

    private fun run(vararg args: String): String {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(*args)
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            val cmd = args.joinToString(" ")
            val stdoutStr = stdout.toString(Charsets.UTF_8)
            val stderrStr = stderr.toString(Charsets.UTF_8)
            error("FAILED (${result.exitValue}): $cmd\n$stdoutStr\n$stderrStr")
        }
        return stdout.toString(Charsets.UTF_8)
    }

    /**
     * Return the set of all globally-visible, defined symbol names in a Mach-O
     * static library using `xcrun nm`.
     */
    private fun extractGlobalDefinedSymbols(libPath: File): Set<String> {
        val output = run("xcrun", "nm", "-g", "--defined-only", libPath.absolutePath)
        val symbols = mutableSetOf<String>()
        for (line in output.lineSequence()) {
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size == 3 && parts[1].length == 1 && parts[1][0].isUpperCase()) {
                symbols.add(parts[2])
            }
        }
        return symbols
    }

    companion object {
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }
}
