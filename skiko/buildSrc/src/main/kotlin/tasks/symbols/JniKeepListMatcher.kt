package tasks.symbols

/**
 * Matches symbol names that belong to the JNI / JVM infrastructure and must be
 * preserved in the linker keep-list across all JVM-host platforms.
 *
 * The single anchored regex covers the three name shapes produced by C/JNI
 * compilers we support:
 *
 * - Linux x64 / Windows x64 / Android: no leading underscore (`Java_org_…`,
 *   `JNI_OnLoad`).
 * - macOS / iOS: a leading underscore from the Mach-O ABI (`_Java_org_…`,
 *   `_JNI_OnLoad`).
 *
 * Replaces the previous Linux-vs-non-Linux `contains(...)` branch which never
 * matched the no-underscore Windows-x64 names.
 */
private val JNI_REGEX = Regex(
    "^_?(Java_|JNI_OnLoad|JNI_OnUnload|JNICALL\\b|JNI[A-Za-z]|jvm[A-Za-z_])"
)

internal fun isJniInfrastructureSymbol(name: String): Boolean =
    JNI_REGEX.containsMatchIn(name)
