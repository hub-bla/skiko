import org.gradle.api.Project
import java.io.File

enum class TargetEnv { JVM, NATIVE, WASM }
enum class Linkage { STATIC, DIRECT_STATIC, DYNAMIC }
enum class BinaryFlagUsage { COMPILE, LINK }
enum class SkikoModuleKind { CORE, EXTENSION }

data class ResolvedSkikoBinaryInputs(
    val staticLibBaseNames: List<String> = emptyList(),
    val staticArchivePaths: List<String> = emptyList(),
    val directStaticLibBaseNames: List<String> = emptyList(),
    val directStaticArchivePaths: List<String> = emptyList(),
    val dynamicLibNames: List<String> = emptyList(),
    val compileFlags: List<String> = emptyList(),
    val linkFlags: List<String> = emptyList(),
    val includeDirs: List<File> = emptyList(),
)

@DslMarker
annotation class SkikoBinaryModuleDsl

val OS.validEnvs: List<TargetEnv>
    get() = when (this) {
        OS.MacOS, OS.Linux -> listOf(TargetEnv.JVM, TargetEnv.NATIVE)
        OS.Windows, OS.Android -> listOf(TargetEnv.JVM)
        OS.IOS, OS.TVOS -> listOf(TargetEnv.NATIVE)
        OS.Wasm -> listOf(TargetEnv.WASM)
    }

class DependencyRegistry {
    private data class Rule(
        val os: OS,
        val arch: Arch?,
        val env: TargetEnv,
        val linkage: Linkage,
        val libs: List<String>
    )

    private data class FlagRule(
        val os: OS,
        val arch: Arch?,
        val env: TargetEnv,
        val usage: BinaryFlagUsage,
        val flags: List<String>
    )

    private val rules = mutableListOf<Rule>()
    private val flagRules = mutableListOf<FlagRule>()

    fun on(os: OS, env: TargetEnv, linkage: Linkage, vararg libs: String, arch: Arch? = null) {
        rules.add(Rule(os, arch, env, linkage, libs.toList()))
    }

    fun flags(os: OS, env: TargetEnv, usage: BinaryFlagUsage, vararg flags: String, arch: Arch? = null) {
        flagRules.add(FlagRule(os, arch, env, usage, flags.toList()))
    }

    fun on(linkage: Linkage, vararg libs: String) {
        for (os in OS.values()) {
            for (env in os.validEnvs) {
                on(os, env, linkage, *libs)
            }
        }
    }

    fun flags(usage: BinaryFlagUsage, vararg flags: String) {
        for (os in OS.values()) {
            for (env in os.validEnvs) {
                flags(os, env, usage, *flags)
            }
        }
    }

    fun getLibs(os: OS, arch: Arch, env: TargetEnv, linkage: Linkage): List<String> {
        return rules.filter { it.os == os && (it.arch == null || it.arch == arch) && it.env == env && it.linkage == linkage }
            .flatMap { it.libs }
    }

    fun getFlags(os: OS, arch: Arch, env: TargetEnv, usage: BinaryFlagUsage): List<String> {
        return flagRules.filter { it.os == os && (it.arch == null || it.arch == arch) && it.env == env && it.usage == usage }
            .flatMap { it.flags }
    }
}

@SkikoBinaryModuleDsl
class SkikoExtensionModuleDsl internal constructor(
    private val registry: DependencyRegistry
) {
    internal var dependsOnCore: Boolean = false
        private set

    fun dependsOnCore() {
        dependsOnCore = true
    }

    fun binary(configure: BinaryDependencyScope.() -> Unit) {
        BinaryDependencyScope(registry).configure()
    }

    fun dependencies(configure: DependencyRegistry.() -> Unit) {
        registry.configure()
    }
}

@SkikoBinaryModuleDsl
class BinaryDependencyScope internal constructor(
    private val registry: DependencyRegistry,
    private val osFilter: Set<OS>? = null,
    private val archFilter: Set<Arch>? = null,
    private val envFilter: Set<TargetEnv>? = null,
) {
    fun allTargets(configure: BinaryDependencyScope.() -> Unit) {
        configure()
    }

    fun jvm(configure: BinaryDependencyScope.() -> Unit) {
        restrict(envs = setOf(TargetEnv.JVM)).configure()
    }

    fun kotlinNative(configure: BinaryDependencyScope.() -> Unit) {
        restrict(envs = setOf(TargetEnv.NATIVE)).configure()
    }

    fun wasm(configure: BinaryDependencyScope.() -> Unit) {
        restrict(oses = setOf(OS.Wasm), arches = setOf(Arch.Wasm), envs = setOf(TargetEnv.WASM)).configure()
    }

    fun x64(configure: BinaryDependencyScope.() -> Unit) {
        restrict(arches = setOf(Arch.X64)).configure()
    }

    fun arm64(configure: BinaryDependencyScope.() -> Unit) {
        restrict(arches = setOf(Arch.Arm64)).configure()
    }

    fun linux(configure: BinaryDependencyScope.() -> Unit) {
        restrict(oses = setOf(OS.Linux)).configure()
    }

    fun macos(configure: BinaryDependencyScope.() -> Unit) {
        restrict(oses = setOf(OS.MacOS)).configure()
    }

    fun windows(configure: BinaryDependencyScope.() -> Unit) {
        restrict(oses = setOf(OS.Windows)).configure()
    }

    fun android(configure: BinaryDependencyScope.() -> Unit) {
        restrict(oses = setOf(OS.Android)).configure()
    }

    fun ios(configure: BinaryDependencyScope.() -> Unit) {
        restrict(oses = setOf(OS.IOS)).configure()
    }

    fun tvos(configure: BinaryDependencyScope.() -> Unit) {
        restrict(oses = setOf(OS.TVOS)).configure()
    }

    fun darwin(configure: BinaryDependencyScope.() -> Unit) {
        restrict(oses = setOf(OS.MacOS, OS.IOS, OS.TVOS)).configure()
    }

    fun staticSkiaLibs(vararg libs: String) {
        matchingTargets().forEach { target ->
            registry.on(target.os, target.env, Linkage.STATIC, *libs, arch = target.arch)
        }
    }

    fun directStaticSkiaLibs(vararg libs: String) {
        matchingTargets().forEach { target ->
            registry.on(target.os, target.env, Linkage.DIRECT_STATIC, *libs, arch = target.arch)
        }
    }

    fun dynamicSystemLibs(vararg libs: String) {
        matchingTargets().forEach { target ->
            registry.on(target.os, target.env, Linkage.DYNAMIC, *libs, arch = target.arch)
        }
    }

    fun compileFlags(vararg flags: String) {
        matchingTargets().forEach { target ->
            registry.flags(target.os, target.env, BinaryFlagUsage.COMPILE, *flags, arch = target.arch)
        }
    }

    fun linkFlags(vararg flags: String) {
        matchingTargets().forEach { target ->
            registry.flags(target.os, target.env, BinaryFlagUsage.LINK, *flags, arch = target.arch)
        }
    }

    fun appleFrameworks(vararg frameworks: String) {
        linkFlags(*frameworks.flatMap { listOf("-framework", it) }.toTypedArray())
    }

    private fun restrict(
        oses: Set<OS>? = null,
        arches: Set<Arch>? = null,
        envs: Set<TargetEnv>? = null,
    ): BinaryDependencyScope {
        return BinaryDependencyScope(
            registry = registry,
            osFilter = osFilter.intersectIfPresent(oses),
            archFilter = archFilter.intersectIfPresent(arches),
            envFilter = envFilter.intersectIfPresent(envs),
        )
    }

    private data class TargetMatch(val os: OS, val arch: Arch?, val env: TargetEnv)

    private fun matchingTargets(): List<TargetMatch> =
        OS.values().flatMap { os ->
            os.validEnvs.map { env -> TargetMatch(os, archFilter?.singleOrNull(), env) }
        }.filter { (os, _, env) ->
            (osFilter == null || os in osFilter) && (envFilter == null || env in envFilter)
        }

    private fun <T> Set<T>?.intersectIfPresent(other: Set<T>?): Set<T>? =
        when {
            this == null -> other
            other == null -> this
            else -> intersect(other)
        }
}

fun skikoExtensionModule(
    name: String,
    configure: SkikoExtensionModuleDsl.() -> Unit
): SkikoExtensionModule {
    val registry = DependencyRegistry()
    val moduleDsl = SkikoExtensionModuleDsl(registry).apply(configure)
    return SkikoExtensionModule(
        name = name,
        kind = SkikoModuleKind.EXTENSION,
        dependsOnCore = moduleDsl.dependsOnCore,
        registry = registry
    )
}

@SkikoBinaryModuleDsl
class SkikoModulesDsl internal constructor() {
    private val modules = mutableListOf<SkikoExtensionModule>()

    fun core(configure: SkikoExtensionModuleDsl.() -> Unit) {
        val registry = DependencyRegistry()
        SkikoExtensionModuleDsl(registry).configure()
        modules.add(
            SkikoExtensionModule(name = "core", kind = SkikoModuleKind.CORE, registry = registry)
        )
    }

    fun module(
        name: String,
        configure: SkikoExtensionModuleDsl.() -> Unit
    ) {
        modules.add(skikoExtensionModule(name, configure))
    }

    internal fun build(): List<SkikoExtensionModule> = modules.toList()
}

fun skikoModules(configure: SkikoModulesDsl.() -> Unit): List<SkikoExtensionModule> =
    SkikoModulesDsl().apply(configure).build()


class SkikoExtensionModule(
    val name: String,
    val kind: SkikoModuleKind = SkikoModuleKind.EXTENSION,
    val dependsOnCore: Boolean = false,
    private val registry: DependencyRegistry = DependencyRegistry()
) {
    val projectPath: String = if (kind == SkikoModuleKind.CORE) ":" else ":skiko-$name"
    val libBaseName: String = if (kind == SkikoModuleKind.CORE) "skiko" else "skiko-$name"
    val nativeBridgesLibPrefix: String =
        if (kind == SkikoModuleKind.CORE) "skiko-native-bridges" else "skiko-$name-native-bridges"
    val cinteropName: String = if (kind == SkikoModuleKind.CORE) "skiko" else "skiko-$name"

    fun staticLibBaseNames(os: OS, arch: Arch, env: TargetEnv): List<String> =
        registry.getLibs(os, arch, env, Linkage.STATIC)

    fun directStaticLibBaseNames(os: OS, arch: Arch, env: TargetEnv): List<String> =
        registry.getLibs(os, arch, env, Linkage.DIRECT_STATIC)

    private fun archivePaths(os: OS, libBaseNames: List<String>, skiaBinDir: String): List<String> {
        val prefix = if (os == OS.Windows) "" else "lib"
        val suffix = when (os) {
            OS.Windows -> ".lib"
            OS.Wasm -> ".wasm.a"
            else -> ".a"
        }

        return libBaseNames.map { baseName ->
            "$skiaBinDir/$prefix$baseName$suffix"
        }
    }

    fun staticArchivePaths(os: OS, arch: Arch, env: TargetEnv, skiaBinDir: String): List<String> =
        archivePaths(os, staticLibBaseNames(os, arch, env), skiaBinDir)

    fun directStaticArchivePaths(os: OS, arch: Arch, env: TargetEnv, skiaBinDir: String): List<String> =
        archivePaths(os, directStaticLibBaseNames(os, arch, env), skiaBinDir)

    fun dynamicLibNames(os: OS, arch: Arch, env: TargetEnv): List<String> =
        registry.getLibs(os, arch, env, Linkage.DYNAMIC)

    fun resolveBinaryInputs(os: OS, arch: Arch, env: TargetEnv, skiaBinDir: String): ResolvedSkikoBinaryInputs {
        return ResolvedSkikoBinaryInputs(
            staticLibBaseNames = staticLibBaseNames(os, arch, env),
            staticArchivePaths = staticArchivePaths(os, arch, env, skiaBinDir),
            directStaticLibBaseNames = directStaticLibBaseNames(os, arch, env),
            directStaticArchivePaths = directStaticArchivePaths(os, arch, env, skiaBinDir),
            dynamicLibNames = dynamicLibNames(os, arch, env),
            compileFlags = registry.getFlags(os, arch, env, BinaryFlagUsage.COMPILE),
            linkFlags = registry.getFlags(os, arch, env, BinaryFlagUsage.LINK),
        )
    }
}

private val allSkikoModules = skikoModules {
    core {
        binary {
            allTargets {
                staticSkiaLibs(
                    "skia",
                    "skia_ganesh_ext",
                    "skresources",
                    "skparagraph",
                    "icu",
                    "svg",
                    "png",
                    "webp_sse41",
                    "skunicode_core",
                    "skunicode_icu",
                    "webp",
                    "harfbuzz",
                    "expat",
                    "zlib",
                    "jpeg",
                    "skshaper",
                )
            }
            jvm {
                macos {
                    linkFlags("-lobjc")
                    appleFrameworks(
                        "AppKit",
                        "CoreFoundation",
                        "CoreGraphics",
                        "CoreServices",
                        "CoreText",
                        "Foundation",
                        "IOKit",
                        "Metal",
                        "OpenGL",
                        "QuartzCore",
                    )
                }
                windows {
                    staticSkiaLibs("d3d12allocator")
                }
                linux {
                    directStaticSkiaLibs(
                        "skia",
                        "skia_ganesh_ext",
                        "skshaper",
                        "skunicode_icu",
                        "skunicode_core",
                    )
                    dynamicSystemLibs("GL", "X11", "fontconfig", "expat")
                    arm64 {
                        dynamicSystemLibs("EGL")
                    }
                }
                android {
                    dynamicSystemLibs("EGL")
                }
            }
            kotlinNative {
                staticSkiaLibs(
                    "dng_sdk",
                    "piex",
                )
                macos {
                    appleFrameworks("Metal", "CoreGraphics", "CoreText", "CoreServices")
                }
                ios {
                    appleFrameworks("Metal", "CoreGraphics", "CoreText", "UIKit")
                }
                tvos {
                    appleFrameworks("Metal", "CoreGraphics", "CoreText", "UIKit")
                }
                linux {
                    dynamicSystemLibs("fontconfig", "GL")
                    arm64 {
                        dynamicSystemLibs("EGL")
                    }
                }
            }
            wasm {
                staticSkiaLibs(
                    "skia",
                    "skia_ganesh_ext",
                    "bentleyottmann",
                    "freetype2",
                    "jpeg12",
                    "jpeg16",
                    "wuffs",
                    "skcms",
                    "brotli",
                )
                linkFlags(
                    "-s",
                    "MAIN_MODULE=2",
                    "-s", "MAX_WEBGL_VERSION=2",
                    "-s", "MIN_WEBGL_VERSION=2",
                    "-s","MODULARIZE=1",
                    "-s", "EXPORT_NAME=loadSkikoWASM",
                    "-s", "EXPORTED_RUNTIME_METHODS=\"[GL, wasmExports, loadDynamicLibrary, LDSO, locateFile, HEAP8, HEAPU8, HEAP16, HEAPU16, HEAP32, HEAPU32, HEAPF32, HEAPF64]\"",
                    "--bind",
                )
            }
        }
    }

    module("skottie") {
        dependsOnCore()
        binary {
            allTargets {
                staticSkiaLibs("skottie", "sksg", "jsonreader")
            }
            linux {
                jvm {
                    dynamicSystemLibs("expat")
                }
            }
        }
    }
}

val skikoCoreModule: SkikoExtensionModule = allSkikoModules.single { it.kind == SkikoModuleKind.CORE }
private val allSkikoExtensionModules = allSkikoModules.filter { it.kind == SkikoModuleKind.EXTENSION }

fun Project.skikoExtensionModules(): List<SkikoExtensionModule> =
    allSkikoExtensionModules.filter { findProject(it.projectPath) != null }

fun Project.currentSkikoExtensionModuleOrNull(): SkikoExtensionModule? =
    skikoExtensionModules().firstOrNull { it.projectPath == path }

fun Project.currentSkikoExtensionModule(): SkikoExtensionModule =
    currentSkikoExtensionModuleOrNull()
        ?: error("No extension module descriptor registered for project path '$path'")
