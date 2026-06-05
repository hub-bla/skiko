package tasks.symbols

/**
 * A linker-visible symbol parsed from a tool output (`nm`, `dumpbin`).
 */
internal data class Symbol(
    val name: String,
    val type: SymbolType,
)

internal enum class SymbolType {
    /** Defined global text/data/bss/rodata/weak/etc. */
    DefinedGlobal,
    /** Undefined reference (`U`). */
    Undefined,
}
