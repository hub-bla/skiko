import org.gradle.api.Project
import java.io.File

data class SkikoExtensionModule(
    val projectPath: String,
    val libBaseName: String,
    val nativeBridgesLibPrefix: String,
    val cinteropName: String,
    val ownedStaticLibBaseNames: List<String>,
    val nativeLinuxExtraLibBaseNames: List<String> = emptyList(),
    val jvmExtraStaticArchiveBaseNamesByOs: Map<OS, List<String>> = emptyMap(),
    val jvmExtraDynamicLibNamesByOs: Map<OS, List<String>> = emptyMap()
) {
    fun staticLibraryPaths(
        skiaDir: String,
        targetString: String,
        buildType: SkiaBuildType
    ): List<String> {
        val (libraryFilePrefix, libraryFileSuffix) = when {
            targetString.contains("windows", ignoreCase = true) -> "" to ".lib"
            targetString.contains("wasm", ignoreCase = true) -> "lib" to ".wasm.a"
            else -> "lib" to ".a"
        }
        val skiaBinDir = File("$skiaDir/out/${buildType.id}-$targetString")
        return ownedStaticLibBaseNames
            .map { baseName -> File(skiaBinDir, "$libraryFilePrefix$baseName$libraryFileSuffix") }
            .filter { it.exists() }
            .map { it.absolutePath }
    }

    fun jvmExtraStaticArchivePaths(os: OS, skiaBinDir: String): List<String> =
        jvmExtraStaticArchiveBaseNamesByOs[os].orEmpty().map { "$skiaBinDir/lib$it.a" }

    fun jvmExtraDynamicLibNames(os: OS): List<String> =
        jvmExtraDynamicLibNamesByOs[os].orEmpty()
}

private val allSkikoExtensionModules = listOf(
    SkikoExtensionModule(
        projectPath = ":skiko-skottie",
        libBaseName = "skiko-skottie",
        nativeBridgesLibPrefix = "skiko-skottie-native-bridges",
        cinteropName = "skiko-skottie",
        ownedStaticLibBaseNames = listOf("skottie", "sksg", "jsonreader"),
        nativeLinuxExtraLibBaseNames = listOf("skottie"),
        jvmExtraStaticArchiveBaseNamesByOs = mapOf(OS.Linux to listOf("sksg", "jsonreader")),
        jvmExtraDynamicLibNamesByOs = mapOf(OS.Linux to listOf("expat"))
    ),
    SkikoExtensionModule(
        projectPath = ":skiko-graphite",
        libBaseName = "skiko-graphite",
        nativeBridgesLibPrefix = "skiko-graphite-native-bridges",
        cinteropName = "skiko-graphite",
        ownedStaticLibBaseNames = listOf("skia_graphite_ext", "skia_graphite_dawn_ext", "dawn_combined"),
        nativeLinuxExtraLibBaseNames = listOf("skia_graphite_ext")
    )
)

fun Project.skikoExtensionModules(): List<SkikoExtensionModule> =
    allSkikoExtensionModules.filter { findProject(it.projectPath) != null }

fun Project.currentSkikoExtensionModule(): SkikoExtensionModule =
    skikoExtensionModules().firstOrNull { it.projectPath == path }
        ?: error("No extension module descriptor registered for project path '$path'")
