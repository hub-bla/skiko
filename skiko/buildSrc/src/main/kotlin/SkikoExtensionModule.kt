import org.gradle.api.Project
import java.io.File

data class SkikoExtensionModule(
    val projectPath: String,
    val libBaseName: String,
    val nativeBridgesLibPrefix: String,
    val cinteropName: String,
    val ownedStaticLibBaseNames: List<String>,
    val nativeLinuxExtraLibBaseNames: List<String> = emptyList(),
    val jvmLinuxExtraLibBaseNames: List<String> = emptyList()
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
        return ownedStaticLibBaseNames.map { baseName ->
            File(skiaBinDir, "$libraryFilePrefix$baseName$libraryFileSuffix").absolutePath
        }
    }
}

private val allSkikoExtensionModules = listOf(
    SkikoExtensionModule(
        projectPath = ":skiko-skottie",
        libBaseName = "skiko-skottie",
        nativeBridgesLibPrefix = "skiko-skottie-native-bridges",
        cinteropName = "skiko-skottie",
        ownedStaticLibBaseNames = listOf("skottie", "sksg", "jsonreader"),
        nativeLinuxExtraLibBaseNames = listOf("skottie"),
        jvmLinuxExtraLibBaseNames = listOf("sksg", "jsonreader")
    )
)

fun Project.skikoExtensionModules(): List<SkikoExtensionModule> =
    allSkikoExtensionModules.filter { findProject(it.projectPath) != null }

fun Project.currentSkikoExtensionModule(): SkikoExtensionModule =
    skikoExtensionModules().firstOrNull { it.projectPath == path }
        ?: error("No extension module descriptor registered for project path '$path'")
