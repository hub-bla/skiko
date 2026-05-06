package tasks.symbols

/**
 * Parser for Apple `.tbd` (Text-Based Dylib) stubs shipped in the macOS SDK.
 *
 * TBD files are YAML 1.1 documents (versions 1–4). Linker-visible names appear
 * under several keys, each potentially repeated per `targets:` array:
 *
 * - `symbols:`            — plain C symbols (already prefixed with `_`).
 * - `weak-symbols:` /
 *   `weak-defs:`           — same shape as `symbols:`.
 * - `re-exports:`         — names re-exported from other dylibs.
 * - `objc-classes:`       — ObjC class names; produce `_OBJC_CLASS_$_X` and
 *                           `_OBJC_METACLASS_$_X`.
 * - `objc-eh-types:`      — produce `_OBJC_EHTYPE_$_X`.
 * - `objc-ivars:`         — ivar names verbatim (already mangled).
 *
 * Lists may be inline (`[ a, b, c ]`) or span multiple lines until a closing
 * `]`. Tokens may be single-quoted in TBD v3+. `$ld$` linker directives are
 * dropped — they are not real symbols.
 *
 * The parser is line-based and version-agnostic; if a future TBD revision adds
 * a new symbol-bearing key, just extend [TbdKey].
 */

private enum class TbdKey {
    Symbols, WeakSymbols, ReExports, ObjcClasses, ObjcEhTypes, ObjcIvars,
}

private val KEY_PATTERN = Regex(
    "^\\s*(symbols|weak-symbols|weak-defs|re-exports|reexports|objc-classes|objc-eh-types|objc-ivars)\\s*:\\s*(.*)$"
)

private fun keyOf(name: String): TbdKey? = when (name) {
    "symbols" -> TbdKey.Symbols
    "weak-symbols", "weak-defs" -> TbdKey.WeakSymbols
    "re-exports", "reexports" -> TbdKey.ReExports
    "objc-classes" -> TbdKey.ObjcClasses
    "objc-eh-types" -> TbdKey.ObjcEhTypes
    "objc-ivars" -> TbdKey.ObjcIvars
    else -> null
}

internal fun parseTbd(text: String): Sequence<String> = sequence {
    var current: TbdKey? = null
    val buf = StringBuilder()

    fun flush(): Sequence<String> = sequence {
        val key = current ?: return@sequence
        val raw = buf.toString()
        val open = raw.indexOf('[')
        val close = raw.lastIndexOf(']')
        if (open < 0 || close <= open) return@sequence
        for (token in raw.substring(open + 1, close).split(',')) {
            val name = token.trim().removeSurrounding("'").removeSurrounding("\"").trim()
            if (name.isEmpty() || name.startsWith("\$ld\$")) continue
            when (key) {
                TbdKey.Symbols, TbdKey.WeakSymbols, TbdKey.ReExports -> yield(name)
                TbdKey.ObjcClasses -> {
                    val bare = name.removePrefix("_")
                    yield("_OBJC_CLASS_\$_$bare")
                    yield("_OBJC_METACLASS_\$_$bare")
                }
                TbdKey.ObjcEhTypes -> {
                    val bare = name.removePrefix("_")
                    yield("_OBJC_EHTYPE_\$_$bare")
                }
                TbdKey.ObjcIvars -> yield(name)
            }
        }
    }

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trimEnd('\r', '\n')
        if (current != null) {
            buf.append(' ').append(line)
            if (']' in line) {
                yieldAll(flush())
                current = null
                buf.setLength(0)
            }
            continue
        }
        val match = KEY_PATTERN.matchEntire(line) ?: continue
        val key = keyOf(match.groupValues[1]) ?: continue
        val rest = match.groupValues[2]
        if (rest.isEmpty()) continue // value on next lines without inline `[`; skipped (no list yet)
        current = key
        buf.setLength(0)
        buf.append(rest)
        if (']' in rest) {
            yieldAll(flush())
            current = null
            buf.setLength(0)
        }
    }
}
