package tasks.symbols

/**
 * A linker-visible symbol parsed from a tool output (`nm`, `dumpbin`, `.tbd`).
 *
 * The parsers in this package are intentionally pure (no Gradle/exec dependencies)
 * so they can be unit-tested with captured fixtures.
 */
internal data class Symbol(
    val name: String,
    val type: SymbolType,
    val defined: Boolean,
)

internal enum class SymbolType {
    /** Defined global text/data/bss/rodata/weak/etc. (uppercase nm letters T/D/B/R/W/V/A). */
    DefinedGlobal,
    /** Undefined reference (`U`). */
    Undefined,
    /** Undefined weak reference (`w`). */
    UndefinedWeak,
    /** Other categories we surface but don't currently filter on (locals, etc.). */
    Other,
}
