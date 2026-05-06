package tasks.symbols

/**
 * Parser for `dumpbin /SYMBOLS` output (Windows COFF object files / static libs).
 *
 * Each symbol row uses the documented column layout, e.g.:
 * ```
 * 000 00000000 SECT1  notype       External     | SymbolName
 * 001 00000000 UNDEF  notype ()    External     | OtherSymbol
 * ```
 *
 * We keep only rows whose storage class is `External` (other classes — `Static`,
 * `Label`, `Filename`, `WeakExternal` aux records, etc. — are linkage-internal
 * and should be ignored). The section column is `UNDEF` for undefined refs and
 * `SECTn` / `ABS` / `DEBUG` / `COMMON` for defined symbols.
 *
 * Also drops MSVC compiler-generated names that are never real C/C++ exports:
 * `__imp_X` (IAT thunks), `.refptr.X`, `__real@hex`, `__xmm@hex`, `??_C@…`
 * (string literals), and any quoted decorated names.
 */

// A `dumpbin /SYMBOLS` row has roughly this shape:
//   <idx> <value> <section> <type...> <storage> | <name> [demangled hint]
//
// The COFF symbol name itself never contains whitespace; dumpbin, however, may
// append a human-readable demangled description after the mangled name, e.g.:
//   008 0 SECT4 notype () External | ??$foo@H@@YAXHH@Z (void __cdecl foo<int>(int, int))
// Capturing greedily (or lazily with `\s*$`) included that trailing description —
// spaces and commas leaked into the generated `symbols.def` and broke `lld-link`
// with errors like `unknown directive: ,`. Restrict the name to a single
// non-whitespace token; everything after it (including the demangled hint) is
// silently dropped.
private val DUMPBIN_SYMBOLS_LINE = Regex(buildString {
    val hex          = """[0-9A-Fa-f]+"""
    val ws           = """\s+"""
    val section      = """(\S+)"""        // SECTn / UNDEF / ABS / DEBUG / COMMON
    val typeColumn   = """.*?"""          // e.g. "notype" or "notype ()" — can contain spaces
    val storageClass = """(External|Static|Label|Filename|WeakExternal|EndOfFunction|BeginFunction)"""
    val name         = """(\S+)"""        // single non-whitespace token, the real mangled name
    val demangledHint = """(?:\s.*)?"""   // optional human-readable demangled description

    append("^")
    append(hex).append(ws)                // index
    append(hex).append(ws)                // value
    append(section).append(ws)
    append(typeColumn).append(ws)
    append(storageClass).append(ws)
    append("""\|""").append(ws)
    append(name).append(demangledHint).append("$")
})

private fun isCompilerGeneratedName(name: String): Boolean =
    name.startsWith("__imp_") ||
        name.startsWith(".refptr") ||
        name.startsWith("__real@") ||
        name.startsWith("__xmm@") ||
        name.startsWith("??_C@") ||
        name.startsWith("\"")

internal fun parseDumpbinSymbols(output: String): Sequence<Symbol> = sequence {
    for (rawLine in output.lineSequence()) {
        val line = rawLine.trimEnd('\r', '\n', ' ', '\t')
        if (line.isEmpty()) continue
        val m = DUMPBIN_SYMBOLS_LINE.find(line) ?: continue
        val section = m.groupValues[1]
        val storage = m.groupValues[2]
        if (storage != "External") continue
        val name = m.groupValues[3].trim()
        if (name.isEmpty() || isCompilerGeneratedName(name)) continue
        val defined = section != "UNDEF"
        yield(Symbol(name, if (defined) SymbolType.DefinedGlobal else SymbolType.Undefined, defined))
    }
}
