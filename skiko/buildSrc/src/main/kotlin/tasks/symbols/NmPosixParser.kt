package tasks.symbols

/**
 * Parser for `nm -P` (POSIX) output.
 *
 * The POSIX format is column-stable across GNU binutils nm, LLVM nm, the
 * macOS/BSD nm, and `aarch64-linux-gnu-nm`:
 *
 * ```
 * <name> <type> [value] [size]
 * ```
 *
 * - `<type>` is a single letter; uppercase = defined global, lowercase = local,
 *   `U` = undefined, `w` = undefined weak.
 * - File-header lines in multi-file mode look like `path/to/file.o:` and are
 *   skipped structurally.
 *
 * The parser is intentionally pure (no exec, no logger) and returns a
 * `Sequence<Symbol>` so callers can apply their own filters.
 */

private val NM_POSIX_LINE = Regex(buildString {
    // Symbol names produced by C/C++ compilers may start with a letter, `_`, `$`, `.`,
    // `?` (MSVC mangling) or `@` and may further contain digits and `@` (GLIBC versioning).
    val name      = """([A-Za-z_$.?@][\w$.?@]*)"""
    val typeLetter = """([A-Za-z])"""        // single-letter type column, e.g. T/D/U/w/S/...
    val optional  = """(?:\s+\S+)?"""        // optional value/size columns
    val ws        = """\s+"""

    append("^").append(name).append(ws).append(typeLetter)
    append(optional).append(optional)        // value, size (both optional)
    append("""\s*$""")
})

internal fun parseNmPosix(output: String): Sequence<Symbol> = sequence {
    for (rawLine in output.lineSequence()) {
        val line = rawLine.trimEnd('\r', '\n', ' ', '\t')
        if (line.isEmpty()) continue
        // File-header lines: `path/to/file.o:` or `libfoo.a[bar.o]:`.
        if (line.endsWith(':')) continue
        val m = NM_POSIX_LINE.matchEntire(line) ?: continue
        val name = m.groupValues[1]
        val typeLetter = m.groupValues[2][0]
        // Per `man nm`: uppercase letter = global/external symbol, lowercase = local.
        // `U` = undefined reference, `w` = undefined weak reference. Every other
        // uppercase letter (T text, D data, B bss, R rodata, W/V weak, A absolute,
        // S symbol in a non-standard section — e.g. macOS vtables/typeinfo, C
        // common, I indirect) denotes a defined global. Restricting this to a
        // hand-picked subset of letters silently drops legitimate exports
        // (notably `S` on macOS, which covers C++ vtables/typeinfo).
        val type = when {
            typeLetter == 'U' -> SymbolType.Undefined
            typeLetter == 'w' -> SymbolType.UndefinedWeak
            typeLetter.isUpperCase() -> SymbolType.DefinedGlobal
            else -> SymbolType.Other
        }
        val defined = type == SymbolType.DefinedGlobal
        yield(Symbol(name, type, defined))
    }
}
