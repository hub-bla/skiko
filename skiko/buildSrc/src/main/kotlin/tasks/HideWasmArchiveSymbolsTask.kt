package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Rewrites copied LLVM WebAssembly object archives so Skia implementation
 * symbols are hidden before they are linked into a side module.
 *
 * llvm-objcopy currently accepts `--set-symbols-visibility` for wasm objects
 * but does not mutate the `WASM_SYMBOL_TABLE` flags that wasm-ld uses. The wasm
 * object format stores hidden visibility as bit 0x4 in each symbol's linking
 * metadata, so this task patches that bit directly on defined non-local symbols.
 */
abstract class HideWasmArchiveSymbolsTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArchives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun hideSymbols() {
        val outDir = outputDir.get().asFile
        project.delete(outDir)
        outDir.mkdirs()

        inputArchives.files.filter { it.isFile }.forEachIndexed { archiveIndex, inputArchive ->
            val outputArchive = outDir.resolve(inputArchive.name)
            val extractDir = outDir.resolve("objects-$archiveIndex")
            val memberNamesOutput = ByteArrayOutputStream()

            extractDir.mkdirs()

            execOperations.exec {
                executable = "emar"
                args("t", inputArchive.absolutePath)
                standardOutput = memberNamesOutput
            }

            execOperations.exec {
                executable = "emar"
                workingDir = extractDir
                args("x", inputArchive.absolutePath)
            }

            val memberNames = memberNamesOutput.toString()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()

            var hidden = 0
            memberNames.forEach { memberName ->
                val member = extractDir.resolve(memberName)
                if (member.isFile && member.name.endsWith(".o")) {
                    hidden += hideDefinedNonLocalSymbols(member)
                }
            }

            execOperations.exec {
                executable = "emar"
                workingDir = extractDir
                args(listOf("crs", outputArchive.absolutePath) + memberNames)
            }

            logger.lifecycle("hideWasmArchiveSymbols: ${inputArchive.name}: hidden $hidden symbols")
        }
    }

    private fun hideDefinedNonLocalSymbols(file: File): Int {
        val bytes = file.readBytes()
        require(bytes.size >= 8 && bytes.sliceArray(0 until 8).contentEquals(WASM_MAGIC)) {
            "${file.absolutePath} is not a wasm object"
        }

        val data = bytes.copyOf()
        var position = 8
        var changed = 0

        while (position < data.size) {
            val sectionId = data[position++].toInt() and 0xff
            val sectionSize = readUleb(data, position)
            position = sectionSize.next
            val sectionStart = position
            val sectionEnd = sectionStart + sectionSize.value

            if (sectionId == CUSTOM_SECTION_ID) {
                val sectionName = readName(data, position)
                if (sectionName.value == LINKING_SECTION_NAME) {
                    changed += hideLinkingSectionSymbols(data, sectionName.next, sectionEnd)
                }
            }

            position = sectionEnd
        }

        if (changed > 0) {
            file.writeBytes(data)
        }
        return changed
    }

    private fun hideLinkingSectionSymbols(data: ByteArray, start: Int, end: Int): Int {
        var position = readUleb(data, start).next // linking metadata version
        var changed = 0

        while (position < end) {
            val subsectionType = data[position++].toInt() and 0xff
            val subsectionSize = readUleb(data, position)
            position = subsectionSize.next
            val subsectionStart = position
            val subsectionEnd = subsectionStart + subsectionSize.value

            if (subsectionType == WASM_SYMBOL_TABLE_SUBSECTION) {
                changed += hideSymbolTable(data, subsectionStart)
            }

            position = subsectionEnd
        }

        return changed
    }

    private fun hideSymbolTable(data: ByteArray, start: Int): Int {
        var position = readUleb(data, start).next
        var changed = 0

        repeat(readUleb(data, start).value) {
            val kind = data[position++].toInt() and 0xff
            val flags = readUleb(data, position)
            position = flags.next

            val symbolFlags = flags.value
            val isDefined = symbolFlags and WASM_SYM_UNDEFINED == 0
            val isLocal = symbolFlags and WASM_SYM_BINDING_LOCAL != 0
            val isHidden = symbolFlags and WASM_SYM_VISIBILITY_HIDDEN != 0

            if (isDefined && !isLocal && !isHidden) {
                writeUlebSameWidth(data, flags.start, flags.end, symbolFlags or WASM_SYM_VISIBILITY_HIDDEN)
                changed++
            }

            position = skipSymbol(data, position, kind, symbolFlags)
        }

        return changed
    }

    private fun skipSymbol(data: ByteArray, start: Int, kind: Int, flags: Int): Int {
        var position = start
        return when (kind) {
            SYMTAB_FUNCTION, SYMTAB_GLOBAL, SYMTAB_EVENT, SYMTAB_TABLE -> {
                position = readUleb(data, position).next
                if (flags and WASM_SYM_UNDEFINED == 0 || flags and WASM_SYM_EXPLICIT_NAME != 0) {
                    position = readName(data, position).next
                }
                position
            }

            SYMTAB_DATA -> {
                position = readName(data, position).next
                if (flags and WASM_SYM_UNDEFINED == 0) {
                    position = readUleb(data, position).next // data segment index
                    position = readUleb(data, position).next // offset
                    position = readUleb(data, position).next // size
                }
                position
            }

            SYMTAB_SECTION -> readUleb(data, position).next
            else -> error("Unknown wasm symbol kind $kind")
        }
    }

    private fun readName(data: ByteArray, start: Int): Name {
        val size = readUleb(data, start)
        val bytesStart = size.next
        val bytesEnd = bytesStart + size.value
        return Name(String(data, bytesStart, size.value), bytesEnd)
    }

    private fun readUleb(data: ByteArray, start: Int): Uleb {
        var position = start
        var result = 0
        var shift = 0
        while (true) {
            val byte = data[position++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) {
                return Uleb(result, start, position)
            }
            shift += 7
        }
    }

    private fun writeUlebSameWidth(data: ByteArray, start: Int, end: Int, value: Int) {
        var remaining = value
        for (index in start until end) {
            var byte = remaining and 0x7f
            remaining = remaining ushr 7
            if (index != end - 1) {
                byte = byte or 0x80
            }
            data[index] = byte.toByte()
        }
        check(remaining == 0) {
            "Cannot write ULEB value $value into ${end - start} byte(s)"
        }
    }

    private data class Uleb(val value: Int, val start: Int, val end: Int) {
        val next: Int get() = end
    }

    private data class Name(val value: String, val next: Int)

    private companion object {
        val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)

        const val CUSTOM_SECTION_ID = 0
        const val LINKING_SECTION_NAME = "linking"
        const val WASM_SYMBOL_TABLE_SUBSECTION = 8

        const val SYMTAB_FUNCTION = 0
        const val SYMTAB_DATA = 1
        const val SYMTAB_GLOBAL = 2
        const val SYMTAB_SECTION = 3
        const val SYMTAB_EVENT = 4
        const val SYMTAB_TABLE = 5

        const val WASM_SYM_BINDING_LOCAL = 0x2
        const val WASM_SYM_VISIBILITY_HIDDEN = 0x4
        const val WASM_SYM_UNDEFINED = 0x10
        const val WASM_SYM_EXPLICIT_NAME = 0x40
    }
}
