package tasks.configuration

import Arch
import CompileSkikoCppTask
import OS
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
import java.io.File
import SkiaGPUBackend

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
    os: OS, arch: Arch, isUikitSim: Boolean, backend: SkiaGPUBackend
): TaskProvider<CompileSkikoCppTask> = with(this.project) {
    val skiaNativeDir = registerOrGetSkiaDirProvider(os, arch, isUikitSim = isUikitSim)
    val actionName = "compileNativeBridges".withSuffix(isUikitSim = isUikitSim)

    return project.registerSkikoTask<CompileSkikoCppTask>(actionName, os, arch, backend) {
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
                    *skiaPreprocessorFlags(OS.IOS, buildType, backend),
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
                    *skiaPreprocessorFlags(OS.TVOS, buildType, backend),
                ))
            }
            OS.MacOS -> {
                flags.set(listOf(
                    *buildType.clangFlags,
                    *skiaPreprocessorFlags(OS.MacOS, buildType, backend),
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
                    *skiaPreprocessorFlags(OS.Linux, buildType, backend)
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
    staticLibraries: List<String> = emptyList(),
    libraryPaths: List<String> = emptyList(),
    dependsOnTasks: List<Any> = emptyList(),
    backend: SkiaGPUBackend,
) {
    val tasks = target.project.tasks
    val taskNameSuffix = joinToTitleCamelCase(
        os.idWithSuffix(isUikitSim = target.isUikitSimulator()), arch.id, backend.id
    )
    val writeCInteropDef = tasks.register(
        "writeCInteropDef$taskNameSuffix", WriteCInteropDefFile::class.java
    ) {
        this.linkerOpts.set(linkerOpts)
        this.staticLibraries.set(staticLibraries)
        this.libraryPaths.set(libraryPaths)
        outputFile.set(project.layout.buildDirectory.file("cinterop/$targetString/${backend.id}/skiko.def"))
    }

    tasks.withType(CInteropProcess::class.java).configureEach {
        if (konanTarget == target.konanTarget) {
            dependsOn(writeCInteropDef)
            dependsOnTasks.forEach { dependsOn(it) }
            doLast {
                val klibFile = outputFileProvider.get().absoluteFile
                if (!klibFile.exists()) return@doLast
                val tmpFile = File(klibFile.parent, "${klibFile.name}.tmp")
                java.util.zip.ZipInputStream(klibFile.inputStream().buffered()).use { zin ->
                    java.util.zip.ZipOutputStream(tmpFile.outputStream().buffered()).use { zout ->
                        var entry = zin.nextEntry
                        while (entry != null) {
                            zout.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            if (entry.name == "default/manifest") {
                                val patched = zin.readBytes()
                                    .toString(Charsets.UTF_8)
                                    .lines()
                                    .filter { line ->
                                        !line.startsWith("libraryPaths=") &&
                                                !line.startsWith("staticLibraries=")
                                    }
                                    .joinToString("\n")
                                zout.write(patched.toByteArray(Charsets.UTF_8))
                            } else {
                                zin.copyTo(zout)
                            }
                            zout.closeEntry()
                            entry = zin.nextEntry
                        }
                    }
                }
                tmpFile.renameTo(klibFile)
            }
        }
    }




    // Use backend-suffixed cinterop name so both don't clash on the same target
    val backendCinteropName = "${cinteropName}-${backend.id}"
    target.compilations.getByName("main") {
        cinterops.create(backendCinteropName).apply {
            defFileProperty.set(writeCInteropDef.map { it.outputFile.get().asFile })
        }
    }
}

fun skiaStaticLibraries(skiaDir: String, targetString: String, buildType: SkiaBuildType): List<String> {
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
    ).map {
        "$skiaBinSubdir/$it"
    }
}

@OptIn(ExperimentalStdlibApi::class)
fun SkikoProjectContext.configureNativeTarget(os: OS, arch: Arch, target: KotlinNativeTarget) = with(this.project) {
    if (!os.isCompatibleWithHost) return

    target.generateVersion(os, arch, skiko)
    val isUikitSim = target.isUikitSimulator()
    val targetString = "${os.idWithSuffix(isUikitSim = isUikitSim)}-${arch.id}"

    val unzipper = registerOrGetSkiaDirProvider(os, arch, isUikitSim)
    val unpackedSkia = unzipper.get()
    val skiaDir = unpackedSkia.absolutePath
    val skiaBinDir = "$skiaDir/out/${buildType.id}-$targetString"

    val allStaticLibraries = skiaStaticLibraries(skiaDir, targetString, buildType)
    val staticLibFileNames = allStaticLibraries.map { File(it).name }
    val libraryPaths = allStaticLibraries.map { File(it).parent }.distinct()

    // Run the full compile→link→cinterop pipeline once per backend
    SkiaGPUBackend.values().forEach { backend ->
        val crossCompileTask = compileNativeBridgesTask(os, arch, isUikitSim, backend)

        val linkActionName = "linkNativeBridges".withSuffix(isUikitSim = isUikitSim)
        val linkTask = project.registerSkikoTask<Exec>(linkActionName, os, arch, backend) {
            dependsOn(crossCompileTask)
            val objectFilesDir = crossCompileTask.map { it.outDir.get() }
            val objectFiles = project.fileTree(objectFilesDir) { include("**/*.o") }
            inputs.files(objectFiles)
            val outDir = layout.buildDirectory
                .dir("nativeBridges/static/$targetString/${backend.id}")
                .get().asFile
            val staticLib = "skiko-native-bridges-$targetString-${backend.id}.a"
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
            .file("nativeBridges/static/$targetString/${backend.id}/skiko-native-bridges-$targetString-${backend.id}.a")
            .get().asFile.absolutePath

        val allLibsForBackend = allStaticLibraries + bridgesLibraryPath
        val staticLibFileNamesForBackend = allLibsForBackend.map { File(it).name }
        val libraryPathsForBackend = allLibsForBackend.map { File(it).parent }.distinct()

        val linkerFlags = when (os) {
            OS.MacOS -> {
                val macFrameworks = listOfFrameworks("Metal", "CoreGraphics", "CoreText", "CoreServices")
                configureCinterop(
                    cinteropName = "skiko",
                    os = os, arch = arch, target = target,
                    targetString = targetString,
                    linkerOpts = macFrameworks,
                    staticLibraries = staticLibFileNamesForBackend,
                    libraryPaths = libraryPathsForBackend,
                    dependsOnTasks = listOf(linkTask),
                    backend = backend
                )
                mutableListOfLinkerOptions(macFrameworks)
            }
            OS.IOS -> {
                val iosFrameworks = listOfFrameworks("Metal", "CoreGraphics", "CoreText", "UIKit")
                configureCinterop(
                    cinteropName = "uikit",
                    os = os, arch = arch, target = target,
                    targetString = targetString,
                    linkerOpts = iosFrameworks,
                    staticLibraries = staticLibFileNamesForBackend,
                    libraryPaths = libraryPathsForBackend,
                    dependsOnTasks = listOf(linkTask),
                    backend = backend
                )
                mutableListOfLinkerOptions(iosFrameworks)
            }
            OS.TVOS -> {
                val tvosFrameworks = listOfFrameworks("Metal", "CoreGraphics", "CoreText", "UIKit")
                configureCinterop(
                    cinteropName = "uikit",
                    os = os, arch = arch, target = target,
                    targetString = targetString,
                    linkerOpts = tvosFrameworks,
                    staticLibraries = staticLibFileNamesForBackend,
                    libraryPaths = libraryPathsForBackend,
                    dependsOnTasks = listOf(linkTask),
                    backend = backend
                )
                mutableListOfLinkerOptions(tvosFrameworks)
            }
            OS.Linux -> {
                val options = mutableListOf(
                    "-L/usr/lib64",
                    "-L/usr/lib/${if (arch == Arch.Arm64) "aarch64" else "x86_64"}-linux-gnu",
                    "-lfontconfig", "-lGL",
                    "$skiaBinDir/libskottie.a",
                    "$skiaBinDir/libjsonreader.a",
                    "$skiaBinDir/libsksg.a",
                    "$skiaBinDir/libskshaper.a",
                    "$skiaBinDir/libskunicode_core.a",
                    "$skiaBinDir/libskunicode_icu.a",
                    "$skiaBinDir/libskia.a"
                )
                if (arch == Arch.Arm64) options.add("-lEGL")
                if (arch == Arch.Arm64 && hostArch != Arch.Arm64) {
                    options.add(0, "-L/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc/lib64")
                    options.add(1, "-L/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc/usr/lib64")
                }
                configureCinterop(
                    cinteropName = "skiko",
                    os = os, arch = arch, target = target,
                    targetString = targetString,
                    linkerOpts = mutableListOfLinkerOptions(options),
                    staticLibraries = staticLibFileNamesForBackend,
                    libraryPaths = libraryPathsForBackend,
                    dependsOnTasks = listOf(linkTask),
                    backend = backend
                )
                mutableListOfLinkerOptions(options)
            }
            else -> mutableListOf()
        }

        if (skiko.includeTestHelpers) {
            linkerFlags.addAll(when (os) {
                OS.Linux -> listOf("-linker-option", "-lX11", "-linker-option", "-lGLX")
                else -> emptyList()
            })
        }

        target.binaries.all { freeCompilerArgs += linkerFlags }
        target.compilations.all {
            kotlinOptions { freeCompilerArgs += linkerFlags }
            compileTaskProvider.configure { dependsOn(linkTask) }
        }
    }

    // We remove them since we publish them separately
    // with backend variants in configureNativeCinteropPublications.
    project.afterEvaluate {
        project.configurations
            .findByName("${target.name}ApiElements")
            ?.artifacts
            ?.removeIf { it.file.name.contains("cinterop") }
    }
}

fun KotlinMultiplatformExtension.configureIOSTestsWithMetal(project: Project) {
    val metalTestTargets = listOf("iosX64", "iosSimulatorArm64")
    metalTestTargets.forEach { target: String ->
        if (targets.names.contains(target)) {
            val testBinary = targets.getByName<KotlinNativeTarget>(target).binaries.getTest("DEBUG")
            project.tasks.register(target + "TestWithMetal") {
                dependsOn(testBinary.linkTask)
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