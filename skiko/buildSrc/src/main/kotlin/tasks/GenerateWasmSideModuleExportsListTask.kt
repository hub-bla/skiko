package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import tasks.symbols.SymbolType
import tasks.symbols.parseNmPosix
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Computes the public ABI of a WASM side module.
 *
 * `SIDE_MODULE=1` exports all visible symbols. For Skiko extension side modules
 * we only need the Kotlin/JS + Kotlin/Wasm bridge entry points generated in the
 * module's own C++ glue. Everything from Skia static archives is private module
 * implementation detail.
 */
abstract class GenerateWasmSideModuleExportsListTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFiles
    abstract val objectFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val defined = extractDefinedSymbols(objectFiles.files.toList())
        val exported = defined
            .filter(::isWasmSideModuleBridgeSymbol)
            .sorted()

        outDir.resolve("symbols_filtered.txt").writeText(exported.joinToString("\n"))
        outDir.resolve("exported_functions.txt").writeText(
            exported.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { "\"_${it}\"" }
        )

        logger.lifecycle("generateWasmSideModuleExportsList: defined=${defined.size}, exported=${exported.size}")
    }

    private fun extractDefinedSymbols(files: List<File>): List<String> {
        val actualFiles = files.filter { it.isFile }
        if (actualFiles.isEmpty()) return emptyList()

        val output = run("emnm", listOf("-P", "-g", "--defined-only"), actualFiles)
        return parseNmPosix(output)
            .filter { it.type == SymbolType.DefinedGlobal }
            .map { it.name }
            .distinct()
            .toList()
    }

    private fun run(executable: String, args: List<String>, files: List<File>): String {
        val combinedOutput = StringBuilder()
        val errorOutputBuilder = StringBuilder()
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
        } catch (t: Throwable) {
            logger.error(
                "generateWasmSideModuleExportsList: FATAL. Failed to run '$executable'.\n" +
                        "Args=$args\n" +
                        "FirstFile=${files.firstOrNull()?.absolutePath.orEmpty()}\n" +
                        "Stderr Output=$errorOutputBuilder\n" +
                        "PATH=${System.getenv("PATH")}",
                t
            )
            throw t
        }
        return combinedOutput.toString()
    }
}

private val WASM_SIDE_MODULE_BRIDGE_REGEX = Regex(
    "^(org_jetbrains_|" +
            "disposeCallback$|" +
            "callVoidCallback$|" +
            "callBooleanCallback$|" +
            "callIntCallback$|" +
            "callNativePointerCallback$|" +
            "callInteropPointerCallback$)"
)

internal fun isWasmSideModuleBridgeSymbol(name: String): Boolean =
    WASM_SIDE_MODULE_BRIDGE_REGEX.containsMatchIn(name)
