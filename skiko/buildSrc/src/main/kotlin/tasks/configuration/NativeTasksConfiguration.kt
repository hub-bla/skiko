package tasks.configuration

import Arch
import CompileSkikoCppTask
import PatchSkiaSymbolsTask
import OS
import SkiaBuildType
import SkikoExtensionModule
import SkikoModuleKind
import SkikoProjectContext
import WriteCInteropDefFile
import compilerForTarget
import currentSkikoExtensionModuleOrNull
import hostArch
import isCompatibleWithHost
import joinToTitleCamelCase
import mutableListOfLinkerOptions
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import projectDirs
import registerOrGetSkiaDirProvider
import registerSkikoTask
import skiaVersion
import skikoCoreModule
import java.io.File

fun String.withSuffix(isUikitSim: Boolean = false) =
    this + if (isUikitSim) "Sim" else ""

fun KotlinTarget.isUikitSimulator() =
    name.contains("Simulator", ignoreCase = true) || name == "tvosX64" // x64 tvOS is implicitly a simulator

fun Project.findXcodeSdkRoot(): String {
    val defaultPath = "/Applications/Xcode.app/Contents/Developer/Platforms"
    if (File(defaultPath).exists()) {
        return defaultPath.also {
            println("findXcodeSdkRoot = $it")
        }
    }

    return (project.property("skiko.ci.xcodehome") as? String)?.let {
        val sdkPath = it + "/Platforms"
        println("findXcodeSdkRoot = $sdkPath")
        sdkPath
    } ?: error("gradle property `skiko.ci.xcodehome` is not set")
}

fun SkikoProjectContext.compileNativeBridgesTask(
    os: OS, arch: Arch, isUikitSim: Boolean
): TaskProvider<CompileSkikoCppTask> = with (this.project) {
    val skiaNativeDir = registerOrGetSkiaDirProvider(os, arch, isUikitSim = isUikitSim)

    val actionName = "compileNativeBridges".withSuffix(isUikitSim = isUikitSim)

    return project.registerSkikoTask<CompileSkikoCppTask>(actionName, os, arch) {
        dependsOn(skiaNativeDir)
        val unpackedSkia = skiaNativeDir.get()
        val targetString = "${os.idWithSuffix(isUikitSim = isUikitSim)}-${arch.id}"
        val binaryModule = project.currentSkikoExtensionModuleOrNull() ?: skikoCoreModule
        val moduleBinaryInputs = binaryModule.resolveBinaryInputs(
            os,
            arch,
            TargetEnv.NATIVE,
            unpackedSkia.resolve("out/${buildType.id}-$targetString").absolutePath
        )

        compiler.set(compilerForTarget(os, arch))
        buildTargetOS.set(os)
        if (isUikitSim) {
            buildSuffix.set("sim")
        }
        buildTargetArch.set(arch)
        buildVariant.set(buildType)

        when (os) {
            OS.IOS -> {
                val sdkRoot = findXcodeSdkRoot()
                val iphoneOsSdk = "$sdkRoot/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"
                val iphoneSimSdk = "$sdkRoot/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"
                val iosArchFlags = when (arch) {
                    Arch.Arm64 -> arrayOf(
                        "-target", if (isUikitSim) "arm64-apple-ios-simulator" else "arm64-apple-ios",
                        "-isysroot", if (isUikitSim) iphoneSimSdk else iphoneOsSdk,
                        if (isUikitSim) "-mios-simulator-version-min=12.0" else "-mios-version-min=12.0"
                    )
                    Arch.X64 -> arrayOf(
                        "-target", "x86_64-apple-ios-simulator",
                        "-mios-version-min=12.0",
                        "-isysroot", iphoneSimSdk
                    )
                    else -> throw GradleException("Unsupported arch: $arch")
                }
                flags.set(listOf(
                    *iosArchFlags,
                    *buildType.clangFlags,
                    "-stdlib=libc++",
                    *skiaPreprocessorFlags(OS.IOS, buildType),
                ))
            }
            OS.TVOS -> {
                val sdkRoot = findXcodeSdkRoot()
                val tvOsSdk = "$sdkRoot/AppleTVOS.platform/Developer/SDKs/AppleTVOS.sdk"
                val tvSimSdk = "$sdkRoot/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator.sdk"
                val tvosArchFlags = when (arch) {
                    Arch.Arm64 -> arrayOf(
                        "-target", if (isUikitSim) "arm64-apple-tvos-simulator" else "arm64-apple-tvos",
                        if (isUikitSim) "-mappletvsimulator-version-min=12.0" else "-mappletvos-version-min=12.0" ,
                        "-isysroot", if (isUikitSim) tvSimSdk else tvOsSdk,
                    )
                    Arch.X64 -> arrayOf(
                        "-target", "x86_64-apple-tvos-simulator",
                        "-mappletvsimulator-version-min=12.0",
                        "-isysroot", tvSimSdk
                    )
                    else -> throw GradleException("Unsupported arch: $arch")
                }
                flags.set(listOf(
                    *tvosArchFlags,
                    *buildType.clangFlags,
                    "-stdlib=libc++",
                    *skiaPreprocessorFlags(OS.TVOS, buildType),
                ))
            }
            OS.MacOS -> {
                compiler.set(project.appleToolchainExecutableOrDefault("clang++", compiler.get()))
                flags.set(listOf(
                    *project.appleMacOsSdkFlags().toTypedArray(),
                    *buildType.clangFlags,
                    *skiaPreprocessorFlags(OS.MacOS, buildType),
                    when(arch) {
                        Arch.Arm64 -> "-arch arm64"
                        Arch.X64 -> "-arch x86_64"
                        else -> error("Unexpected arch: $arch for $os")
                    }
                ))
            }
            OS.Linux -> {
                val archFlags = if (arch == Arch.Arm64) arrayOf(
                    "-mno-outline-atomics",
                ) else arrayOf()
                val linuxFlags = mutableListOf(
                    *buildType.clangFlags,
                    "-fPIC",
                    "-fno-rtti",
                    "-fno-exceptions",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    *archFlags,
                    *skiaPreprocessorFlags(OS.Linux, buildType)
                )
                // Add sysroot for ARM64 cross-compilation
                if (arch == Arch.Arm64 && hostArch != Arch.Arm64) {
                    linuxFlags.add(0, "--sysroot=/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc")
                }
                flags.set(linuxFlags)
            }
            else -> throw GradleException("$os not yet supported")
        }
        flags.addAll(moduleBinaryInputs.compileFlags)

        val srcDirs = projectDirs("src/commonMain/cpp/common", "src/nativeNativeJs/cpp", "src/nativeJsMain/cpp") +
                if (skiko.includeTestHelpers) projectDirs("src/nativeJsTest/cpp") else emptyList()
        sourceRoots.set(srcDirs)

        includeHeadersNonRecursive(projectDir.resolve("src/nativeJsMain/cpp"))
        if (project.path != ":") {
            includeHeadersNonRecursive(project.rootProject.project(":").projectDir.resolve("src/nativeJsMain/cpp"))
            includeHeadersNonRecursive(project.rootProject.project(":").projectDir.resolve("src/commonMain/cpp/common/include"))
        }
        includeHeadersNonRecursive(projectDir.resolve("src/commonMain/cpp/common/include"))
        includeHeadersNonRecursive(skiaHeadersDirs(unpackedSkia))
    }
}


fun configureCinterop(
    cinteropName: String,
    os: OS,
    arch: Arch,
    target: KotlinNativeTarget,
    targetString: String,
    linkerOpts: List<String>,
) {
    val tasks = target.project.tasks
    val taskNameSuffix = joinToTitleCamelCase(os.idWithSuffix(isUikitSim = target.isUikitSimulator()), arch.id)
    val writeCInteropDef = tasks.register("writeCInteropDef$taskNameSuffix", WriteCInteropDefFile::class.java) {
        this.linkerOpts.set(linkerOpts)
        outputFile.set(project.layout.buildDirectory.file("cinterop/$targetString/skiko.def"))
    }
    tasks.withType(CInteropProcess::class.java).configureEach {
        if (konanTarget == target.konanTarget) {
            dependsOn(writeCInteropDef)
        }
    }
    target.compilations.getByName("main") {
        cinterops.create(cinteropName).apply {
            definitionFile.set(writeCInteropDef.flatMap { it.outputFile })
        }
    }
}

fun skiaStaticLibraries(os: OS, arch: Arch, skiaDir: String, targetString: String, buildType: SkiaBuildType): List<String> {
    val skiaBinSubdir = "$skiaDir/out/${buildType.id}-$targetString"
    return skikoCoreModule
        .resolveBinaryInputs(os, arch, TargetEnv.NATIVE, skiaBinSubdir)
        .staticArchivePaths
}

fun SkikoProjectContext.configureNativeTarget(
    os: OS,
    arch: Arch,
    target: KotlinNativeTarget,
    libPrefix: String,
    cinteropNameProvider: (OS) -> String
) = with(this.project) {
    if (!os.isCompatibleWithHost) return

    target.generateVersion(os, arch, skiko)
    val isUikitSim = target.isUikitSimulator()

    val targetString = "${os.idWithSuffix(isUikitSim = isUikitSim)}-${arch.id}"

    val unzipper = registerOrGetSkiaDirProvider(os, arch, isUikitSim)
    val unpackedSkia = unzipper.get()
    val skiaDir = unpackedSkia.absolutePath
    val skiaBinDir = "$skiaDir/out/${buildType.id}-$targetString"

    val binaryModule = project.currentSkikoExtensionModuleOrNull() ?: skikoCoreModule
    val isCore = binaryModule.kind == SkikoModuleKind.CORE
    val moduleBinaryInputs = binaryModule.resolveBinaryInputs(os, arch, TargetEnv.NATIVE, skiaBinDir)

    // Contextually select core libraries vs extension specific libraries
    val baseLibraries = if (isCore) {
        skiaStaticLibraries(os, arch, skiaDir, targetString, buildType)
    } else {
        moduleBinaryInputs.staticArchivePaths
    }

    val bridgesLibrary = layout.buildDirectory.file(
        "nativeBridges/static/$targetString/${libPrefix}-$targetString.a"
    )
    val bridgesLibraryPath = bridgesLibrary.get().asFile.absolutePath

    // For iOS/tvOS we patch every library so that public Skia symbols are
    // renamed, preventing conflicts when multiple Skia copies are present in
    // the same app binary. Many C++ Itanium-mangled symbols are rewritten by
    // inserting the `skiko` namespace into the mangled name; C symbols and
    // unsupported shapes fall back to a "_skiko" suffix.
    val requiresSymbolPatching = os == OS.IOS || os == OS.TVOS
    val patchedLibsDir = layout.buildDirectory.dir("nativeBridges/patched/$targetString").get().asFile

    val allLibraries = if (requiresSymbolPatching) {
        baseLibraries.map { lib ->
            "${patchedLibsDir.absolutePath}/${File(lib).name}"
        } + "${patchedLibsDir.absolutePath}/${libPrefix}-$targetString.a"
    } else {
        baseLibraries + bridgesLibraryPath
    }

    val linkerFlags = when (os) {
        OS.MacOS -> {
            configureCinterop(cinteropNameProvider(os), os, arch, target, targetString, moduleBinaryInputs.linkFlags)
            mutableListOfLinkerOptions(moduleBinaryInputs.linkFlags)
        }
        OS.IOS -> {
            configureCinterop(cinteropNameProvider(os), os, arch, target, targetString, moduleBinaryInputs.linkFlags)
            mutableListOfLinkerOptions(moduleBinaryInputs.linkFlags)
        }
        OS.TVOS -> {
            configureCinterop(cinteropNameProvider(os), os, arch, target, targetString, moduleBinaryInputs.linkFlags)
            mutableListOfLinkerOptions(moduleBinaryInputs.linkFlags)
        }
        OS.Linux -> {
            val options = mutableListOf(
                "-L/usr/lib64",
                "-L/usr/lib/${if (arch == Arch.Arm64) "aarch64" else "x86_64"}-linux-gnu",
            )
            moduleBinaryInputs.dynamicLibNames.forEach { options.add("-l$it") }

            if (isCore) {
                options.addAll(listOf(
                    "$skiaBinDir/libskshaper.a",
                    "$skiaBinDir/libskunicode_core.a",
                    "$skiaBinDir/libskunicode_icu.a",
                    "$skiaBinDir/libskia.a"
                ))
            } else {
                // Dynamically append native platform dependencies from the extension configuration
                options.addAll(moduleBinaryInputs.staticArchivePaths)
            }
            // When cross-compiling for ARM64 from x64, use the ARM toolchain sysroot
            if (arch == Arch.Arm64 && hostArch != Arch.Arm64) {
                // ARM GNU toolchain sysroot paths
                options.add(0, "-L/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc/lib64")
                options.add(1, "-L/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc/usr/lib64")
            }

            mutableListOfLinkerOptions(options)
        }
        else -> mutableListOf()
    }
    if (os !in setOf(OS.MacOS, OS.IOS, OS.TVOS)) {
        linkerFlags.addAll(mutableListOfLinkerOptions(moduleBinaryInputs.linkFlags))
    }

    if (skiko.includeTestHelpers) {
        linkerFlags.addAll(when (os) {
            OS.Linux -> listOf(
                "-linker-option", "-lX11",
                "-linker-option", "-lGLX",
            )
            else -> emptyList()
        })
    }

    // For some reason since 1.8.0 we need to set freeCompilerArgs for binaries AND for compilations
    target.binaries.all {
        freeCompilerArgs += allLibraries.map { listOf("-include-binary", it) }.flatten() + linkerFlags
    }


    target.compilations.all {
        compilerOptions.configure {
            freeCompilerArgs.addAll(
                allLibraries.flatMap { listOf("-include-binary", it) } + linkerFlags
            )
        }
    }

    val crossCompileTask = compileNativeBridgesTask(os, arch, isUikitSim = isUikitSim)

    // TODO: move to LinkSkikoTask.
    val actionName = "linkNativeBridges".withSuffix(isUikitSim = isUikitSim)
    val linkTask = project.registerSkikoTask<Exec>(actionName, os, arch) {
        dependsOn(crossCompileTask)
        val objectFilesDir = crossCompileTask.map { it.outDir.get() }
        val objectFiles = project.fileTree(objectFilesDir) {
            include("**/*.o")
        }
        inputs.files(objectFiles)
        val outDir = layout.buildDirectory.dir("nativeBridges/static/$targetString").get().asFile
        val staticLib = "$libPrefix-$targetString.a"
        workingDir = outDir
        when (os) {
            OS.Linux -> {
                executable = if (arch == Arch.Arm64 && hostArch != Arch.Arm64) "aarch64-linux-gnu-ar" else "ar"
                argumentProviders.add { listOf("-crs", staticLib) }
            }
            OS.MacOS, OS.IOS, OS.TVOS -> {
                executable = "libtool"
                argumentProviders.add { listOf("-static", "-o", staticLib) }
            }
            else -> error("Unexpected OS for native bridges linking: $os")
        }
        argumentProviders.add { objectFiles.files.map { it.absolutePath } }
        file(outDir).mkdirs()
        outputs.dir(outDir)
    }

    // For iOS/tvOS: patch all Skia + skiko-bridge symbols after linking.
    val compilationDependency = if (requiresSymbolPatching) {
        val patchActionName = "patchSkikoSymbols".withSuffix(isUikitSim = isUikitSim)
        val coreBridgeForSymbols: File? = if (!isCore) {
            project.rootProject.layout.buildDirectory
                .file("nativeBridges/static/$targetString/skiko-native-bridges-$targetString.a")
                .get().asFile
        } else {
            null
        }
        project.registerSkikoTask<PatchSkiaSymbolsTask>(patchActionName, os, arch) {
            dependsOn(unzipper)
            dependsOn(linkTask)
            if (coreBridgeForSymbols != null) {
                val coreLinkTaskName = "linkNativeBridges".withSuffix(isUikitSim = isUikitSim) +
                        joinToTitleCamelCase(os.id, arch.id)
                dependsOn(project.rootProject.tasks.named(coreLinkTaskName))
            }
            skiaLibs.set(baseLibraries.map { File(it) })
            symbolSourceLibs.set(
                if (isCore) {
                    emptyList()
                } else {
                    skiaStaticLibraries(os, arch, skiaDir, targetString, buildType).map { File(it) } +
                            listOfNotNull(coreBridgeForSymbols)
                }
            )
            skikoBridge.set(File(bridgesLibraryPath))
            outputDir.set(patchedLibsDir)
        }
    } else {
        linkTask
    }

    target.compilations.all {
        compileTaskProvider.configure {
            dependsOn(compilationDependency)
        }
    }
}
fun SkikoProjectContext.configureNativeTarget(
    os: OS,
    arch: Arch,
    target: KotlinNativeTarget
) {
    // list of linker options to be included into klib, which are needed for skiko consumers
    // https://github.com/JetBrains/compose-multiplatform/issues/3178
    // Important! Removing or renaming cinterop-uikit publication might cause compile error
    // for projects depending on older Compose/Skiko transitively https://youtrack.jetbrains.com/issue/KT-60399
    val cinteropNameProvider: (OS) -> String = {
        when (it) {
            OS.IOS, OS.TVOS -> "uikit"
            else -> "skiko"
        }
    }

    configureNativeTarget(
        os = os,
        arch = arch,
        target = target,
        libPrefix = "skiko-native-bridges",
        cinteropNameProvider = cinteropNameProvider
    )
}

fun SkikoProjectContext.configureNativeBridgesForExtension(
    module: SkikoExtensionModule,
    os: OS,
    arch: Arch,
    target: KotlinNativeTarget
) = configureNativeTarget(
    os = os,
    arch = arch,
    target = target,
    libPrefix = module.nativeBridgesLibPrefix,
    cinteropNameProvider = { module.cinteropName }
)

fun KotlinMultiplatformExtension.configureIOSTestsWithMetal(project: Project) {
    val metalTestTargets = listOf("iosX64", "iosSimulatorArm64")
    metalTestTargets.forEach { target: String ->
        if (targets.names.contains(target)) {
            val testBinary = targets.getByName<KotlinNativeTarget>(target).binaries.getTest("DEBUG")
            project.tasks.register(target + "TestWithMetal") {
                dependsOn(testBinary.linkTaskProvider)
                doLast {
                    val simulatorIdPropertyKey = "skiko.iosSimulatorUUID"
                    val simulatorId = project.findProperty(simulatorIdPropertyKey)?.toString()
                        ?: error("Property '$simulatorIdPropertyKey' not found. Pass it with -P$simulatorIdPropertyKey=...")

                    project.providers.exec { commandLine("xcrun", "simctl", "boot", simulatorId) }
                    try {
                        project.providers.exec { commandLine("xcrun", "simctl", "spawn", simulatorId, testBinary.outputFile) }
                    } finally {
                        project.providers.exec { commandLine("xcrun", "simctl", "shutdown", simulatorId) }
                    }
                }
            }
        }
    }
}
