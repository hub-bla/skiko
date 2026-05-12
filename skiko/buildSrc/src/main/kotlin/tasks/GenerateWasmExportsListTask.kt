package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import tasks.symbols.SymbolType
import tasks.symbols.parseNmPosix
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Computes the keep-set of WASM exports for the Skiko main module.
 *
 * The set is the intersection of:
 *  - symbols *defined* by the core (skiko `compileWasm` `.o` files + Skia static archives), and
 *  - symbols *imported* (undefined globals) by every WASM side module
 *    (currently only `skiko-skottie`, plus any future Skiko extension module).
 *
 * In addition we always keep the JNI-style infrastructure helpers Skiko ships
 * (`org_jetbrains_skia_*` Kotlin/Wasm + Kotlin/JS bindings, callback shims),
 * so even functions not referenced by any side module survive DCE under
 * `MAIN_MODULE=2`. This is the WASM analogue of [GenerateSymbolsListTask].
 *
 * Output [outputDir] gets three files:
 *  - `core_exports.txt`        – debugging artifact (sorted defined globals).
 *  - `ext_imports.txt`         – debugging artifact (sorted side-module undefineds).
 *  - `exported_functions.txt`  – Emscripten `@response-file` consumed by
 *    `-s EXPORTED_FUNCTIONS=@<file>`, formatted as a JSON-style array
 *    `["_sym1","_sym2",...]` with the leading underscore Emscripten expects on
 *    C symbols.
 */
abstract class GenerateWasmExportsListTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFiles
    abstract val coreObjectFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val coreSkiaArchives: ConfigurableFileCollection

    @get:InputFiles
    abstract val moduleObjectFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val moduleSkiaArchives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        logger.lifecycle(
            "generateWasmExportsList: coreObjects=${coreObjectFiles.files.size}, " +
                    "coreSkiaArchives=${coreSkiaArchives.files.size}, " +
                    "moduleObjects=${moduleObjectFiles.files.size}, " +
                    "moduleSkiaArchives=${moduleSkiaArchives.files.size}"
        )

        val coreExportsFile = outDir.resolve("core_exports.txt")
        val extImportsFile = outDir.resolve("ext_imports.txt")
        val responseFile = outDir.resolve("exported_functions.txt")

        // 1. core defined globals — every symbol the main module is able to export.
        val coreDefined = extractSymbols(
            coreObjectFiles.files.toList() + coreSkiaArchives.files.toList(),
            exported = true,
        )
        coreExportsFile.writeText(coreDefined.sorted().joinToString("\n"))

        // 2. side-module undefined globals — symbols every side module imports.
        //    A symbol is a true cross-module import only if it is undefined in
        //    *every* artifact that ends up linked into the side module
        //    (its own `.o` files plus the Skia archives it owns). Anything
        //    defined within the side module itself is satisfied by the side
        //    module's own link, not by the main module.
        val moduleArtifacts = moduleObjectFiles.files.toList() + moduleSkiaArchives.files.toList()
        val extUndefinedRaw = extractSymbols(moduleArtifacts, exported = false)
        val moduleDefined = extractSymbols(moduleArtifacts, exported = true).toSet()
        val extUndefined = extUndefinedRaw
            .filter { it !in moduleDefined }
            .filter { !isWasmRuntimeProvidedSymbol(it) }
        extImportsFile.writeText(extUndefined.sorted().joinToString("\n"))

        // 3. Keep every cross-module import. We do NOT intersect with
        //    `coreDefined` here: side modules also import compiler-runtime
        //    helpers (e.g. `__cxa_pure_virtual`, `_Znwm`, `_ZdlPv`, …) that are
        //    not defined in our core object files / Skia archives but live in
        //    Emscripten system libraries. Emscripten will pull those in
        //    automatically at link time as long as they appear on
        //    `EXPORTED_FUNCTIONS` for the main module. Listing the full
        //    cross-module-undefined set is therefore both correct and minimal.
        val keep = extUndefined.toMutableSet()

        // 4. Always-keep infrastructure: every Kotlin/Wasm + Kotlin/JS binding
        //    (`org_jetbrains_skia_*`) and the C-side callback helpers shipped in
        //    `common.h`. They are tagged `EMSCRIPTEN_KEEPALIVE`, but we list
        //    them here too as belt-and-braces so the keep-set is fully
        //    deterministic and reproducible.
        keep.addAll(coreDefined.filter(::isWasmInfrastructureSymbol))

        // 5. Emit the @response-file. Emscripten expects exported C symbols
        //    prefixed with `_`. Its `@file` form accepts either a JSON-style
        //    array or a newline-separated list — we use the array form.
        val sortedKeep = keep.sorted()
        responseFile.writeText(
            sortedKeep.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { "\"_${it}\"" }
        )

        logger.lifecycle("generateWasmExportsList: kept ${sortedKeep.size} symbols (out of ${coreDefined.size} core defined globals)")
    }

    private fun extractSymbols(files: List<File>, exported: Boolean): List<String> {
        if (files.isEmpty()) return emptyList()
        val actual = files.filter { it.isFile }
        if (actual.isEmpty()) return emptyList()

        val flags = if (exported) listOf("-P", "-g", "--defined-only") else listOf("-P", "-u")
        val output = run("emnm", flags, actual)
        val wanted = if (exported) SymbolType.DefinedGlobal else SymbolType.Undefined
        val result = mutableSetOf<String>()
        parseNmPosix(output)
            .filter { it.type == wanted }
            .forEach { result.add(it.name) }
        return result.toList()
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
                "generateWasmExportsList: FATAL. Failed to run '$executable'.\n" +
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

private val WASM_INFRA_REGEX = Regex(
    "^(org_jetbrains_skia_|" +
            "disposeCallback$|" +
            "callVoidCallback$|" +
            "callBooleanCallback$|" +
            "callIntCallback$|" +
            "callNativePointerCallback$|" +
            "callInteropPointerCallback$)"
)

internal fun isWasmInfrastructureSymbol(name: String): Boolean =
    WASM_INFRA_REGEX.containsMatchIn(name)

/**
 * Symbols that wasm-ld / Emscripten provide implicitly to dynamically-linked
 * modules and that must not appear in `EXPORTED_FUNCTIONS`. Listing them
 * triggers `emcc: error: undefined exported symbol`, because the linker treats
 * them as dynamic globals rather than ordinary exports.
 */
private val WASM_LINKER_PROVIDED_REGEX = Regex(
    "^(__indirect_function_table$|" +
            "__stack_pointer$|" +
            "__stack_low$|" +
            "__stack_high$|" +
            "__memory_base$|" +
            "__table_base$|" +
            "__heap_base$|" +
            "__heap_end$|" +
            "__data_end$|" +
            "__global_base$|" +
            "__wasm_call_ctors$|" +
            "__wasm_apply_data_relocs$|" +
            "__wasm_init_memory$|" +
            "__wasm_init_tls$|" +
            "GOT\\.|" +
            "env\\.|" +
            // Dawn/WebGPU symbols (C: wgpu*, C++ mangled: _ZN[K]*4wgpu*) are
            // provided by the browser environment when USE_WEBGPU=1 is set on
            // the side module link. They must not appear in the core's
            // EXPORTED_FUNCTIONS.
            "wgpu|" +
            "_ZN[A-Z]*\\d*wgpu)"
)

internal fun isWasmRuntimeProvidedSymbol(name: String): Boolean =
    WASM_LINKER_PROVIDED_REGEX.containsMatchIn(name)