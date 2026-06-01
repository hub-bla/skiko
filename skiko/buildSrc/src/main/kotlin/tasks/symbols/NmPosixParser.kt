package tasks.symbols

private val WHITESPACE = Regex("""\s+""")

internal fun parseNmPosix(
    output: String,
): Sequence<Symbol> =
    output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.endsWith(':') } // file/archive headers
        .mapNotNull(::parseNmPosixLine)

private fun parseNmPosixLine(line: String): Symbol? {
    val columns = line.split(WHITESPACE, limit = 4)
    if (columns.size < 2) return null

    val name = columns[0]
    val typeText = columns[1]
    if (typeText.length != 1) return null

    val typeLetter = typeText[0]
    val type = classifyNmSymbolType(typeLetter)

    return Symbol(
        name = name,
        type = type,
        defined = type == SymbolType.DefinedGlobal,
    )
}

private fun classifyNmSymbolType(
    typeLetter: Char,
): SymbolType =
    when {
        typeLetter == 'U' -> SymbolType.Undefined
        typeLetter == 'w' || typeLetter == 'v' -> SymbolType.UndefinedWeak
        typeLetter.isUpperCase() -> SymbolType.DefinedGlobal

        // GNU unique global symbol.
        typeLetter == 'u' -> SymbolType.DefinedGlobal

        // GNU IFUNC may be printed as lowercase `i`.
        // Treat it as exported only if caller already used `nm -g`.
        typeLetter == 'i' -> SymbolType.DefinedGlobal

        else -> SymbolType.Other
    }