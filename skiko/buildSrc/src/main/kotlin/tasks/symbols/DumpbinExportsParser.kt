package tasks.symbols

/**
 * Parser for `dumpbin /EXPORTS` output.
 *
 * Two distinct shapes share this command:
 *
 * **a)** Import library (`*.lib` for system DLLs):
 * ```
 * Dump of file user32.lib
 * File Type: LIBRARY
 *      Exports
 *        ordinal    name
 *                   CreateWindowExW
 * ```
 *
 * **b)** DLL itself:
 * ```
 *     ordinal hint RVA      name
 *           1    0 00001234 SymbolName
 *           2    1 00001235 OtherSymbol = forwarder.target
 * ```
 *
 * The parser is structural and locale-independent: it recognises the two row
 * shapes by regex and ignores everything else (banners, `File Type:`, blank
 * lines, localised header words…). No English-word blacklist is needed.
 */

// Symbol-name shape used in both forms below: an identifier that may contain
// `_`, `$`, `.`, `?`, `@` (typical for MSVC-mangled and decorated names).
private const val DUMPBIN_NAME = """[A-Za-z_?@$.][\w@$.?]*"""

// DLL form: ordinal hint 8-hex name [= forwarder]
//   e.g. `      1    0 00001234 SymbolName`
//        `      2    1 00001235 OtherSymbol = forwarder.target`
private val DUMPBIN_DLL_EXPORT = Regex(buildString {
    val ordinal     = """(\d+)"""
    val hint        = """\d+"""
    val rva         = """[0-9a-fA-F]{8}"""
    val name        = """($DUMPBIN_NAME)"""
    val forwarder   = """(?:\s+=\s*\S+)?"""    // optional ` = target.symbol`
    val ws          = """\s+"""

    append("""^\s*""")
    append(ordinal).append(ws)
    append(hint).append(ws)
    append(rva).append(ws)
    append(name).append(forwarder).append("""\s*$""")
})

// Import-library form: a deeply-indented bare identifier under "Exports".
// MSVC indents these with at least 11 spaces in practice; we accept >= 6 to be
// tolerant of localised banners and minor formatting drift.
private val DUMPBIN_LIB_EXPORT = Regex("""^\s{6,}($DUMPBIN_NAME)\s*$""")

internal fun parseDumpbinExports(output: String): Sequence<String> = sequence {
    val seen = HashSet<String>()
    for (rawLine in output.lineSequence()) {
        val line = rawLine.trimEnd('\r', '\n', ' ', '\t')
        if (line.isEmpty()) continue
        val name = DUMPBIN_DLL_EXPORT.matchEntire(line)?.groupValues?.get(2)
            ?: DUMPBIN_LIB_EXPORT.matchEntire(line)?.groupValues?.get(1)
            ?: continue
        if (name.isEmpty() || name == "[NONAME]" || name.all { it.isDigit() }) continue
        if (seen.add(name)) yield(name)
    }
}
