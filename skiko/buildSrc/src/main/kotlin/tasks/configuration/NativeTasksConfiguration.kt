package tasks.configuration

import Arch
import CompileSkikoCppTask
import OS
import RequestedSkiaGpuBackend
import SkiaBuildType
import SkikoProjectContext
import WriteCInteropDefFile
import compilerForTarget
import hostArch
import isCompatibleWithHost
import joinToTitleCamelCase
import listOfFrameworks
import mutableListOfLinkerOptions
import org.gradle.api.GradleException
import org.gradle.api.Project
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
import resolveForTarget
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
    os: OS, arch: Arch, isUikitSim: Boolean, backend: RequestedSkiaGpuBackend
): TaskProvider<CompileSkikoCppTask> = with(this.project) {
    val skiaNativeDir = registerOrGetSkiaDirProvider(os, arch, isUikitSim = isUikitSim)
    val actionName = "compileNativeBridges".withSuffix(isUikitSim = isUikitSim)
    val resolvedBackends = listOf(backend).resolveForTarget(os, isNative = true)
    return project.registerSkikoTask<CompileSkikoCppTask>(actionName, os, arch, backend.id) {
        dependsOn(skiaNativeDir)
        val unpackedSkia = skiaNativeDir.get()

        compiler.set(compilerForTarget(os, arch))
        buildTargetOS.set(os)
        buildSuffix.set(if (isUikitSim) "sim-${backend.id}" else backend.id)
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
                    *skiaPreprocessorFlags(OS.IOS, buildType, resolvedBackends),
                ))
            }
            OS.TVOS -> {
                val sdkRoot = findXcodeSdkRoot()
                val tvOsSdk = "$sdkRoot/AppleTVOS.platform/Developer/SDKs/AppleTVOS.sdk"
                val tvSimSdk = "$sdkRoot/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator.sdk"
                val tvosArchFlags = when (arch) {
                    Arch.Arm64 -> arrayOf(
                        "-target", if (isUikitSim) "arm64-apple-tvos-simulator" else "arm64-apple-tvos",
                        if (isUikitSim) "-mappletvsimulator-version-min=12.0" else "-mappletvos-version-min=12.0",
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
                    *skiaPreprocessorFlags(OS.TVOS, buildType, resolvedBackends),
                ))
            }
            OS.MacOS -> {
                flags.set(listOf(
                    *buildType.clangFlags,
                    *skiaPreprocessorFlags(OS.MacOS, buildType, resolvedBackends),
                    when (arch) {
                        Arch.Arm64 -> "-arch arm64"
                        Arch.X64 -> "-arch x86_64"
                        else -> error("Unexpected arch: $arch for $os")
                    }
                ))
            }
            OS.Linux -> {
                val archFlags = if (arch == Arch.Arm64) arrayOf("-mno-outline-atomics") else arrayOf()
                val linuxFlags = mutableListOf(
                    *buildType.clangFlags,
                    "-fPIC",
                    "-fno-rtti",
                    "-fno-exceptions",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    *archFlags,
                    *skiaPreprocessorFlags(OS.Linux, buildType, resolvedBackends)
                )
                if (arch == Arch.Arm64 && hostArch != Arch.Arm64) {
                    linuxFlags.add(0, "--sysroot=/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc")
                }
                flags.set(linuxFlags)
            }
            else -> throw GradleException("$os not yet supported")
        }

        val srcDirs = projectDirs("src/commonMain/cpp/common", "src/nativeNativeJs/cpp", "src/nativeJsMain/cpp") +
                if (skiko.includeTestHelpers) projectDirs("src/nativeJsTest/cpp") else emptyList()
        sourceRoots.set(srcDirs)

        includeHeadersNonRecursive(projectDir.resolve("src/nativeJsMain/cpp"))
        includeHeadersNonRecursive(projectDir.resolve("src/commonMain/cpp/common/include"))
        includeHeadersNonRecursive(skiaHeadersDirs(os, arch, buildType, unpackedSkia))
    }
}


fun configureCinterop(
    cinteropName: String,
    os: OS,
    arch: Arch,
    target: KotlinNativeTarget,
    targetString: String,
    linkerOpts: List<String>,
    staticLibraries: List<String> = emptyList(),
    libraryPaths: List<String> = emptyList(),
    dependsOnTasks: List<Any> = emptyList(),
    backendId: String,
) {
    val tasks = target.project.tasks
    val taskNameSuffix = joinToTitleCamelCase(
        os.idWithSuffix(isUikitSim = target.isUikitSimulator()),
        arch.id,
        backendId
    )
    val writeCInteropDef = tasks.register(
        "writeCInteropDef$taskNameSuffix",
        WriteCInteropDefFile::class.java
    ) {
        this.linkerOpts.set(linkerOpts)
        this.staticLibraries.set(staticLibraries)
        this.libraryPaths.set(libraryPaths)

        this.outputFile.set(
            project.layout.buildDirectory.file(
                "cinterop/$targetString/$backendId/skiko.def"
            )
        )
    }
    val backendCinteropName = joinToTitleCamelCase(cinteropName, backendId)
    target.compilations.getByName("main") {
        cinterops.create(backendCinteropName).apply {
            definitionFile.set(writeCInteropDef.flatMap { it.outputFile })
        }
    }
    tasks.withType(CInteropProcess::class.java).configureEach {
        if (konanTarget == target.konanTarget &&
            name.contains(backendCinteropName, ignoreCase = true)
        ) {
            dependsOn(writeCInteropDef)
            dependsOnTasks.forEach { dependsOn(it) }
        }
    }
}

fun skiaCoreStaticLibraries(
    skiaDir: String,
    targetString: String,
    buildType: SkiaBuildType,
): List<String> {
    val skiaBinSubdir = "$skiaDir/out/${buildType.id}-$targetString"
    return listOf(
        "libskresources.a",
        "libskparagraph.a",
        "libskia.a",
        "libicu.a",
        "libjsonreader.a",
        "libskottie.a",
        "libsvg.a",
        "libpng.a",
        "libwebp_sse41.a",
        "libsksg.a",
        "libskunicode_core.a",
        "libskunicode_icu.a",
        "libwebp.a",
        "libdng_sdk.a",
        "libpiex.a",
        "libharfbuzz.a",
        "libexpat.a",
        "libzlib.a",
        "libjpeg.a",
        "libskshaper.a"
    ).map { "$skiaBinSubdir/$it" }
}

fun nativeLinkerFlags(os: OS, arch: Arch, skiaBinDir: String): List<String> =
    when (os) {
        OS.MacOS -> listOfFrameworks("Metal", "CoreGraphics", "CoreText", "CoreServices")
        // list of linker options to be included into klib, which are needed for skiko consumers
        // https://github.com/JetBrains/compose-multiplatform/issues/3178
        // Important! Removing or renaming cinterop-uikit publication might cause compile error
        // for projects depending on older Compose/Skiko transitively https://youtrack.jetbrains.com/issue/KT-60399
        OS.IOS -> listOfFrameworks("Metal", "CoreGraphics", "CoreText", "UIKit")
        OS.TVOS -> listOfFrameworks("Metal", "CoreGraphics", "CoreText", "UIKit")
        OS.Linux -> {
            val options = mutableListOf(
                "-L/usr/lib64",
                "-L/usr/lib/${if (arch == Arch.Arm64) "aarch64" else "x86_64"}-linux-gnu",
                "-lfontconfig",
                "-lGL",
                // TODO: an ugly hack, Linux linker searches only unresolved symbols.
                "$skiaBinDir/libskottie.a",
                "$skiaBinDir/libjsonreader.a",
                "$skiaBinDir/libsksg.a",
                "$skiaBinDir/libskshaper.a",
                "$skiaBinDir/libskunicode_core.a",
                "$skiaBinDir/libskunicode_icu.a",
                "$skiaBinDir/libskia.a"
            )
            // When cross-compiling for ARM64 from x64, use the ARM toolchain sysroot
            if (arch == Arch.Arm64) options.add("-lEGL")
            if (arch == Arch.Arm64 && hostArch != Arch.Arm64) {
                // ARM GNU toolchain sysroot paths
                options.add(0, "-L/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc/lib64")
                options.add(1, "-L/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc/usr/lib64")
            }
            options
        }
        else -> mutableListOf()
    }

fun SkikoProjectContext.configureNativeTarget(os: OS, arch: Arch, target: KotlinNativeTarget) = with(this.project) {
    if (!os.isCompatibleWithHost) return

    target.generateVersion(os, arch, skiko)
    val isUikitSim = target.isUikitSimulator()

    val targetString = "${os.idWithSuffix(isUikitSim = isUikitSim)}-${arch.id}"

    val unzipper = registerOrGetSkiaDirProvider(os, arch, isUikitSim)
    val unpackedSkia = unzipper.get()
    val skiaDir = unpackedSkia.absolutePath
    val skiaBinDir = "$skiaDir/out/${buildType.id}-$targetString"

    val linkerFlags = nativeLinkerFlags(os, arch, skiaBinDir)
    val linkerOptions = mutableListOfLinkerOptions(linkerFlags).also {flags ->
        if (skiko.includeTestHelpers) {
            flags.addAll(when (os) {
                OS.Linux -> listOf("-linker-option", "-lX11", "-linker-option", "-lGLX")
                else -> emptyList()
            })
        }
    }
    skiko.requestedGpuBackends.forEach { requestedBackend ->
        val resolvedBackends = listOf(requestedBackend).resolveForTarget(os, isNative = true)
        val resolvedBackendsStaticLibs = resolvedBackends.flatMap { it.staticLibraries(os) }.distinct()
            .map { "$skiaBinDir/$it" }

        val crossCompileTask = compileNativeBridgesTask(os, arch, isUikitSim, requestedBackend)
        val linkActionName = "linkNativeBridges".withSuffix(isUikitSim = isUikitSim)
        val linkTask = project.registerSkikoTask<Exec>(linkActionName, os, arch, requestedBackend.id) {
            dependsOn(crossCompileTask)
            val objectFilesDir = crossCompileTask.map { it.outDir.get() }
            val objectFiles = project.fileTree(objectFilesDir) {
                include("**/*.o")
            }
            inputs.files(objectFiles)
            val outDir = layout.buildDirectory
                .dir("nativeBridges/static/$targetString/${requestedBackend.id}")
                .get().asFile
            val staticLib = "skiko-native-bridges-$targetString-${requestedBackend.id}.a"
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

        val bridgesLibraryPath = layout.buildDirectory
            .file("nativeBridges/static/$targetString/${requestedBackend.id}/skiko-native-bridges-$targetString-${requestedBackend.id}.a")
            .get().asFile.absolutePath

        val cinteropLibsForBackend = resolvedBackendsStaticLibs + bridgesLibraryPath
        val cinteropStaticLibFileNames = cinteropLibsForBackend.map { File(it).name }
        val cinteropLibraryPaths = cinteropLibsForBackend.mapNotNull { File(it).parent }.distinct()

        val interopName = when (os) {
            OS.MacOS, OS.Linux -> "skiko"
            OS.IOS, OS.TVOS -> "uikit"
            else -> error("Unexpected OS for native configuration: $os")
        }

        configureCinterop(
            interopName,
            os,
            arch,
            target,
            targetString,
            linkerFlags,
            cinteropStaticLibFileNames,
            cinteropLibraryPaths,
            listOf(linkTask),
            requestedBackend.id
        )
    }

    val coreStaticLibs = skiaCoreStaticLibraries(skiaDir, targetString, buildType)
    val includeBinaryFlags = coreStaticLibs.distinct().flatMap { listOf("-include-binary", it) }

    target.binaries.all {
        freeCompilerArgs += includeBinaryFlags + linkerOptions
    }

    target.compilations.all {
        compileTaskProvider.configure {
            compilerOptions.freeCompilerArgs.addAll(includeBinaryFlags)
            compilerOptions.freeCompilerArgs.addAll(linkerOptions)
        }
    }
    // We remove them since we publish them separately with backend variants in configureNativeCinteropPublications.
    project.afterEvaluate {
        project.configurations.matching { it.name == "${target.name}ApiElements" }.configureEach {
            artifacts.removeIf { it.classifier?.startsWith("cinterop") == true }
            outgoing.artifacts.removeIf { it.classifier?.startsWith("cinterop") == true }
        }
    }
}


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
