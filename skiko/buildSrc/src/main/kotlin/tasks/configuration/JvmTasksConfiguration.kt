package tasks.configuration

import AdditionalRuntimeLibrary
import Arch
import CompileSkikoCppTask
import CompileSkikoObjCTask
import LinkSkikoTask
import OS
import SealAndSignSharedLibraryTask
import SkiaBuildType
import SkikoProjectContext
import compilerForTarget
import dynamicLibExt
import hostArch
import hostOs
import joinToTitleCamelCase
import linkerForTarget
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import projectDirs
import registerOrGetSkiaDirProvider
import registerSkikoTask
import runPkgConfig
import targetId
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText

fun SkikoProjectContext.createCompileJvmBindingsTask(
    targetOs: OS,
    targetArch: Arch,
    skiaJvmBindingsDir: Provider<File>
) = project.registerSkikoTask<CompileSkikoCppTask>("compileJvmBindings", targetOs, targetArch) {
    // Prefer 'java.home' system property to simplify overriding from Intellij.
    // When used from command-line, it is effectively equal to JAVA_HOME.
    if (JavaVersion.current() < JavaVersion.VERSION_17) {
        error("JDK 17+ is required, but Gradle JVM is ${JavaVersion.current()}. " +
                "Check JAVA_HOME (CLI) or Gradle settings (Intellij).")
    }
    val jdkHome = File(System.getProperty("java.home") ?: error("'java.home' is null"))
    dependsOn(skiaJvmBindingsDir)
    buildTargetOS.set(targetOs)
    buildTargetArch.set(targetArch)
    buildSuffix.set("jvm")
    buildVariant.set(buildType)

    val srcDirs = projectDirs(
        "src/commonMain/cpp/common",
        "src/jvmMain/cpp/common",
        "src/awtMain/cpp/common",
        "src/awtMain/cpp/${targetOs.id}",
        "src/jvmTest/cpp"
    )
    sourceRoots.set(srcDirs)
    if (targetOs != OS.Android) includeHeadersNonRecursive(jdkHome.resolve("include"))
    val skiaDir = skiaJvmBindingsDir.get()
    includeHeadersNonRecursive(skiaHeadersDirs(skiaDir))
    val projectDir = project.projectDir
    includeHeadersNonRecursive(projectDir.resolve("src/awtMain/cpp/include"))
    includeHeadersNonRecursive(projectDir.resolve("src/jvmMain/cpp/common"))
    includeHeadersNonRecursive(projectDir.resolve("src/jvmMain/cpp/include"))
    includeHeadersNonRecursive(projectDir.resolve("src/commonMain/cpp/common/include"))
    if (project.path != ":") {
        includeHeadersNonRecursive(project.rootProject.project(":").projectDir.resolve("src/jvmMain/cpp/common"))
        includeHeadersNonRecursive(project.rootProject.project(":").projectDir.resolve("src/commonMain/cpp/common/include"))
    }
    compiler.set(compilerForTarget(targetOs, targetArch))
    val target = targetId(targetOs, targetArch)
    val skiaBinSubdir = "out/${buildType.id}-$target"
    val skiaBinDir = skiaJvmBindingsDir.get().absolutePath + "/" + skiaBinSubdir
    val osFlags: Array<String>
    when (targetOs) {
        OS.MacOS -> {
            compiler.set(project.appleToolchainExecutableOrDefault("clang++", compiler.get()))
            includeHeadersNonRecursive(jdkHome.resolve("include/darwin"))
            osFlags = arrayOf(
                *project.appleMacOsSdkFlags().toTypedArray(),
                *targetOs.clangFlags,
                *buildType.clangFlags,
                "-arch", if (targetArch == Arch.Arm64) "arm64" else "x86_64",
                "-fPIC",
                "-stdlib=libc++",
//                "-fvisibility=hidden",
//                "-fvisibility-inlines-hidden"
            )
        }
        OS.Linux -> {
            includeHeadersNonRecursive(jdkHome.resolve("include/linux"))
            includeHeadersNonRecursive(runPkgConfig("dbus-1"))
            val archFlags = if (targetArch == Arch.Arm64) arrayOf(
                // Always inline atomics for ARM64 to prevent linking incompatibility issues after updating GCC to 10
                "-mno-outline-atomics",
            ) else arrayOf()
            osFlags = arrayOf(
                *buildType.clangFlags,
                "-DGL_GLEXT_PROTOTYPES",
                "-fPIC",
                "-fno-rtti",
                "-fno-exceptions",
                "-fvisibility=hidden",
                "-fvisibility-inlines-hidden",
                *archFlags,
            )
        }
        OS.Windows -> {
            includeHeadersNonRecursive(windowsSdkPaths.includeDirs)
            includeHeadersNonRecursive(jdkHome.resolve("include/win32"))
            includeHeadersNonRecursive(skiaDir.resolve("third_party/externals/angle2/include"))
            includeHeadersNonRecursive(skiaDir.resolve("include/gpu"))
            includeHeadersNonRecursive(skiaDir.resolve("src/gpu"))
            val targetArgs = if (targetArch == Arch.Arm64) arrayOf("/clang:--target=arm64-windows") else arrayOf()
            osFlags = arrayOf(
                "/nologo",
                *buildType.winCompilerFlags,
                "/utf-8",
                "/GR-", // no-RTTI.
                "/FS", // Due to an error when building in Teamcity. https://docs.microsoft.com/en-us/cpp/build/reference/fs-force-synchronous-pdb-writes
                *targetArgs,
            )
        }
        OS.Android -> {
            compiler.set(project.androidClangFor(targetArch))
            osFlags = arrayOf(
                *buildType.clangFlags,
                "-fno-rtti",
                "-fno-exceptions",
                "-fvisibility=hidden",
                "-fPIC"
            )
        }
        OS.Wasm, OS.IOS, OS.TVOS -> error("Should not reach here")
    }

    flags.set(
        listOf(
            *skiaPreprocessorFlags(targetOs, buildType),
            *osFlags
        )
    )
}

fun Provider<String>.orEmpty(): Provider<String> =
    orElse("")

fun Project.androidClangFor(targetArch: Arch, version: String = "30"): Provider<String> {
    val androidArch = when (targetArch) {
        Arch.Arm64 -> "aarch64"
        Arch.X64 -> "x86_64"
        else -> throw GradleException("unsupported $targetArch")
    }
    val hostOsArch = when (hostOs) {
        OS.MacOS -> "darwin-x86_64"
        OS.Linux -> "linux-x86_64"
        OS.Windows -> "windows-x86_64"
        else -> throw GradleException("unsupported $hostOs")
    }
    val ndkPathProvider = project.providers
        .environmentVariable("ANDROID_NDK_HOME")
        .orEmpty()
        .map { ndkHomeEnv ->
            ndkHomeEnv.ifEmpty {
                val androidHome = androidHomePath().get()
                val ndkDir1 = file("$androidHome/ndk")
                val candidates1 = if (ndkDir1.exists()) ndkDir1.list() else emptyArray()
                val ndkVersion =
                    arrayOf(*(candidates1.map { "ndk/$it" }.sortedDescending()).toTypedArray(), "ndk-bundle").find {
                        File(androidHome).resolve(it).exists()
                    } ?: throw GradleException("Cannot find NDK, is it installed (Tools/SDK Manager)?")
                "$androidHome/$ndkVersion"
            }
        }
    return ndkPathProvider.map { ndkPath ->
        var clangBinaryName = "$androidArch-linux-android$version-clang++"
        if (hostOs.isWindows) {
            clangBinaryName += ".cmd"
        }
        "$ndkPath/toolchains/llvm/prebuilt/$hostOsArch/bin/$clangBinaryName"
    }
}


fun SkikoProjectContext.createObjcCompileTask(
    os: OS,
    arch: Arch,
    skiaJvmBindingsDir: Provider<File>
) = project.registerSkikoTask<CompileSkikoObjCTask>("objcCompile", os, arch) {
    dependsOn(skiaJvmBindingsDir)

    val srcDirs = projectDirs(
        "src/awtMain/objectiveC/${os.id}"
    )
    sourceRoots.set(srcDirs)
    val jdkHome = File(System.getProperty("java.home") ?: error("'java.home' is null"))

    includeHeadersNonRecursive(jdkHome.resolve("include"))
    includeHeadersNonRecursive(jdkHome.resolve("include/darwin"))
    includeHeadersNonRecursive(skiaHeadersDirs(skiaJvmBindingsDir.get()))
    val projectDir = project.projectDir
    includeHeadersNonRecursive(projectDir.resolve("src/awtMain/cpp/include"))
    includeHeadersNonRecursive(projectDir.resolve("src/commonMain/cpp/common/include"))
    includeHeadersNonRecursive(projectDir.resolve("src/jvmMain/cpp"))

    compiler.set(project.appleToolchainExecutableOrDefault("clang", "clang"))
    buildVariant.set(buildType)
    buildTargetOS.set(os)
    buildTargetArch.set(arch)
    flags.set(
        listOf(
            "-fobjc-arc",
            "-arch", if (arch == Arch.Arm64) "arm64" else "x86_64",
            *project.appleMacOsSdkFlags().toTypedArray(),
            *os.clangFlags,
            *buildType.clangFlags,
            *skiaPreprocessorFlags(os, buildType),
            "-fPIC"
        )
    )
}

fun generateVersionScript(unexportedTxt: Path, output: Path) {
    val symbols = unexportedTxt.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    output.writeText(buildString {
        appendLine("{")
        appendLine(" local:")
        symbols.forEach { symbol ->
            appendLine("    $symbol;")
        }
        appendLine(" global: *;")
        appendLine("};")
    })
}

fun generateDefFile(exportedTxt: Path, output: Path) {
    val symbols = exportedTxt.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    output.writeText(buildString {
        appendLine("EXPORTS")
        symbols.forEach { symbol ->
            appendLine("    $symbol")
        }
    })
}

fun SkikoProjectContext.createLinkJvmBindings(
    targetOs: OS,
    targetArch: Arch,
    skiaJvmBindingsDir: Provider<File>,
    compileTask: TaskProvider<CompileSkikoCppTask>,
    objcCompileTask: TaskProvider<CompileSkikoObjCTask>?,
    libBaseName: String = "skiko",
    taskSuffix:String = ""
) = project.registerSkikoTask<LinkSkikoTask>("linkJvmBindings$taskSuffix", targetOs, targetArch) {
    val target = targetId(targetOs, targetArch)
    val skiaBinSubdir = "out/${buildType.id}-$target"
    val skiaBinDir = skiaJvmBindingsDir.get().absolutePath + "/" + skiaBinSubdir
    val osFlags: Array<String>

    libFiles = project.fileTree(skiaJvmBindingsDir.map { it.resolve(skiaBinSubdir) }) {
        val fileExtension = if (targetOs.isWindows) ".lib" else ".a"
        val filePrefix = if (targetOs.isWindows) "" else "lib"

        if (libBaseName == "skiko") {
            include("*$fileExtension")
            exclude("${filePrefix}dawn$fileExtension")
            exclude("${filePrefix}skia_graphite_ext$fileExtension")
            exclude("${filePrefix}skia$fileExtension")
            //lld-link: error: duplicate symbol: skcms_MaxRoundtripError
            //>>> defined at skia.lib(skcms.skcms.obj)
            //>>> defined at skcms.lib(skcms.skcms.obj)
            exclude("${filePrefix}skottie$fileExtension")
            exclude("${filePrefix}sksg$fileExtension")
            exclude("${filePrefix}jsonreader$fileExtension")
        }

        if (libBaseName == "skiko-graphite") {
            if (targetOs.isWindows) {
                include("${filePrefix}skia_graphite_dawn_ext$fileExtension")
                include("${filePrefix}dawn$fileExtension")
            } else {
                include("${filePrefix}skia_graphite_ext$fileExtension")
            }
        }

        if (libBaseName == "skiko-skottie") {
            include("${filePrefix}skottie$fileExtension")
            include("${filePrefix}sksg$fileExtension")
            include("${filePrefix}jsonreader$fileExtension")
        }
    }

    dependsOn(compileTask)
    objectFiles = project.fileTree(compileTask.map { it.outDir.get() }) {
        include("**/*.o")
    }
    val libNamePrefix = if (targetOs.isWindows) libBaseName else "lib$libBaseName"
    libOutputFileName.set("$libNamePrefix-${targetOs.id}-${targetArch.id}${targetOs.dynamicLibExt}")
    buildTargetOS.set(targetOs)
    buildSuffix.set("jvm")
    buildTargetArch.set(targetArch)
    buildVariant.set(buildType)
    linker.set(linkerForTarget(targetOs, targetArch))
    val maybeSignedDir = project.layout.buildDirectory.dir("maybe-signed-$target").get().asFile
    when (targetOs) {
        OS.MacOS -> {
            val coreLibDir = project.rootProject.layout.buildDirectory
                .dir("maybe-signed-${targetId(targetOs, targetArch)}").get().asFile

            dependsOn(objcCompileTask!!)
            objectFiles += project.fileTree(objcCompileTask.map { it.outDir.get() }) {
                include("**/*.o")
            }
            val additionalFlags = if (libBaseName != "skiko") {
                val coreMaybeSignTaskName = "maybeSign" + joinToTitleCamelCase(targetOs.id, targetArch.id)
                dependsOn(project.rootProject.tasks.named(coreMaybeSignTaskName))
                arrayOf(
                    "-L${coreLibDir.absolutePath}",
                    "-lskiko-${targetOs.id}-${targetArch.id}",
                )
            } else arrayOf(
                "-Wl,-force_load,$skiaBinDir/libskia.a",
            )
            val exportFlags = if (libBaseName == "skiko" && taskSuffix == "Again") arrayOf(
                // windows - "-Wl,/exclude-symbols:@file.txt"
                // linux - "-Wl, --version-script=symbols.map"
                "-Wl,-unexported_symbols_list,${maybeSignedDir.resolve("symbols_unexported.txt").absolutePath}",
            ) else arrayOf()

            osFlags = arrayOf(
                *targetOs.clangFlags,
                "-arch", if (targetArch == Arch.Arm64) "arm64" else "x86_64",
                "-shared",
                "-dead_strip",
                "-lobjc",
                "-install_name", "./${libOutputFileName.get()}",
                "-current_version", skiko.planeDeployVersion,
                "-framework", "AppKit",
                "-framework", "CoreFoundation",
                "-framework", "CoreGraphics",
                "-framework", "CoreServices",
                "-framework", "CoreText",
                "-framework", "Foundation",
                "-framework", "IOKit",
//                "-framework", "IOSurface",
                "-framework", "Metal",
                "-framework", "OpenGL",
                "-framework", "QuartzCore" // for CoreAnimation
            )  + exportFlags + additionalFlags
        }
        OS.Linux -> {
            val coreLibDir = project.rootProject.layout.buildDirectory
                .dir("maybe-signed-${targetId(targetOs, targetArch)}").get().asFile

            val exportFlags = if (libBaseName == "skiko" && taskSuffix == "Again") {
                val unexportedSymbols = maybeSignedDir.resolve("symbols_unexported.txt")
                val versionScript = maybeSignedDir.resolve("symbols.map")
                doFirst {
                    generateVersionScript(unexportedSymbols.toPath(), versionScript.toPath())
                }
                arrayOf("-Wl,--version-script=${versionScript.absolutePath}")
            } else arrayOf()

            osFlags = mutableListOf<String>().apply {
                addAll(
                    arrayOf(
                        "-shared",
                        // `libstdc++.so.6.*` binaries are forward-compatible and used from GCC 3.4 to 16+,
                        // so do not use `-static-libstdc++` to avoid issues with complex setup.
                        "-static-libgcc",
                        "-lGL",
                        "-lX11",
                        "-lfontconfig",
                        // Enforce immediate symbol resolution at library load time to prevent
                        // lazy-binding issues and make GOT read-only afterwards.
                        "-Wl,-z,relro,-z,now",
                        // Hack to fix problem with linker not always finding certain declarations.
                        "$skiaBinDir/libsksg.a",
                        "$skiaBinDir/libskunicode_core.a",
                        "$skiaBinDir/libskunicode_icu.a",
                        "$skiaBinDir/libskshaper.a",
                        "$skiaBinDir/libjsonreader.a",
                    )
                )
                if (targetArch == Arch.Arm64) {
                    add("-lEGL")
                }
                if (libBaseName == "skiko") {
                    add("-Wl,--whole-archive")
                    add("$skiaBinDir/libskia.a")
                    add("-Wl,--no-whole-archive")
                } else {
                    val coreMaybeSignTaskName = "maybeSign" + joinToTitleCamelCase(targetOs.id, targetArch.id)
                    dependsOn(project.rootProject.tasks.named(coreMaybeSignTaskName))
                    add("-L${coreLibDir.absolutePath}")
                    add("-lskiko-${targetOs.id}-${targetArch.id}")
                }
            }.toTypedArray() + exportFlags
        }
        OS.Windows -> {
            libDirs.set(windowsSdkPaths.libDirs)

            val coreLibDir = project.rootProject.layout.buildDirectory
                .dir("maybe-signed-${targetId(targetOs, targetArch)}").get().asFile

            val exportFlags = if (libBaseName == "skiko" && taskSuffix == "") {
                val defFile = maybeSignedDir.resolve("skiko_all_exports.def")
                val skiaLibPath = "$skiaBinDir/skia.lib"

                doFirst {
                    val out = java.io.ByteArrayOutputStream()
                    project.exec {
                        commandLine("dumpbin", "/SYMBOLS", skiaLibPath)
                        standardOutput = out
                    }

                    val symbols = out.toString().lines()
                        .filter { it.contains("External") && it.contains("|") && !it.contains("UNDEF") }
                        .map { it.substringAfter("|").trim().substringBefore(" ") }
                        .filter { symbol ->
                            symbol.isNotEmpty() &&
                                    !symbol.startsWith("__imp_") &&
                                    !symbol.startsWith(".refptr") &&
                                    !symbol.startsWith("__real@") &&
                                    !symbol.startsWith("__xmm@")
                        }
                        .distinct()
                        .sorted()

                    logger.lifecycle("Pass 1: Exporting ${symbols.size} symbols from skia.lib...")
                    defFile.writeText("EXPORTS\n" + symbols.joinToString("\n") { "    $it" })
                }
                arrayOf("/DEF:${defFile.absolutePath}")

            } else if (libBaseName == "skiko" && taskSuffix == "Again") {
                val exportedSymbols = maybeSignedDir.resolve("symbols_filtered.txt")
                val defFile = maybeSignedDir.resolve("symbols.def")
                doFirst {
                    generateDefFile(exportedSymbols.toPath(), defFile.toPath())
                }
                arrayOf("/DEF:${defFile.absolutePath}")
            } else arrayOf()

            osFlags = mutableListOf<String>().apply {
                addAll(buildType.winLinkerFlags)
                addAll(
                    arrayOf(
                        // ignore https://learn.microsoft.com/en-us/cpp/error-messages/tool-errors/linker-tools-warning-lnk4217
                        // because we link OpenGl dynamically, defining functions in our own file in OpenGLLibrary.cc
                        "/ignore:4217"
                    )
                )
                addAll(
                    arrayOf(
                        "/FORCE:MULTIPLE",
                        "/NOLOGO",
                        "/DLL",
                        "Advapi32.lib",
                        "gdi32.lib",
                        "Dwmapi.lib",
                        "ole32.lib",
                        "Propsys.lib",
                        "shcore.lib",
                        "Shlwapi.lib",
                        "user32.lib",
                        "FontSub.lib",
                        "Usp10.lib"
                    )
                )
                if (buildType == SkiaBuildType.DEBUG) add("dxgi.lib")
                if (libBaseName == "skiko") {
                    add("/WHOLEARCHIVE:$skiaBinDir/skia.lib")
                    add("/IMPLIB:${libBaseName}-${targetOs.id}-${targetArch.id}.lib")
                } else {
                    val coreMaybeSignTaskName = "maybeSign" + joinToTitleCamelCase(targetOs.id, targetArch.id)
                    dependsOn(project.rootProject.tasks.named(coreMaybeSignTaskName))
                    add("/LIBPATH:${coreLibDir.absolutePath}")
                    add("skiko-${targetOs.id}-${targetArch.id}.lib")
                }
            }.toTypedArray() + exportFlags
        }
        OS.Android -> {
            val androidFlags = mutableListOf(
                "-shared",
                "-static-libstdc++",
                "-lEGL",
                "-llog",
            )
            linker.set(project.androidClangFor(targetArch))
            if (libBaseName == "skiko") {
                androidFlags += arrayOf(
                    "-Wl,--whole-archive",
                    "$skiaBinDir/libskia.a",
                    "-Wl,--no-whole-archive"
                )
            }
            osFlags = androidFlags.toTypedArray()
        }
        OS.Wasm, OS.IOS, OS.TVOS -> {
            throw GradleException("This task shalln't be used with $targetOs")
        }
    }
    flags.set(listOf(*osFlags))
}

private val Arch.darwinSignClientName: String
    get() = when (this) {
        Arch.X64 -> "codesign-client-darwin-amd64"
        Arch.Arm64 -> "codesign-client-darwin-arm64"
        else -> error("Unexpected Arch = $this for codesign-client")
    }

fun SkikoProjectContext.createDownloadCodeSignClientDarwinTask(
    targetOs: OS,
    hostArch: Arch
) = project.registerSkikoTask<de.undercouch.gradle.tasks.download.Download>("downloadCodeSignClient", targetOs, hostArch) {
    val fileUrl = "https://codesign-distribution.labs.jb.gg/${hostArch.darwinSignClientName}"

    src(fileUrl)
    dest(project.layout.buildDirectory)
    overwrite(false)

    // only Teamcity agents have access to download the codesign-client executable file
    enabled = this@createDownloadCodeSignClientDarwinTask.skiko.isTeamcityCIBuild

    doLast {
        val downloadedFile = project.layout.buildDirectory.get().asFile.resolve(hostArch.darwinSignClientName)
         downloadedFile.setExecutable(true)
    }
}

fun SkikoProjectContext.maybeSignOrSealTask(
    targetOs: OS,
    targetArch: Arch,
    linkJvmBindings: Provider<LinkSkikoTask>,
    taskSuffix: String =""
) = project.registerSkikoTask<SealAndSignSharedLibraryTask>("maybeSign$taskSuffix", targetOs, targetArch) {
    dependsOn(linkJvmBindings)

    if (targetOs.isMacOs) {
        val downloadCodesignClientTask = "downloadCodeSignClient" + joinToTitleCamelCase(targetOs.id, hostArch.id)
        dependsOn(project.tasks.getByName(downloadCodesignClientTask))
    }

    val linkOutputFile = linkJvmBindings.map { task ->
        task.outDir.get().asFile.walk().single { it.name.endsWith(targetOs.dynamicLibExt) }.absoluteFile
    }
    libFile.set(project.layout.file(linkOutputFile))

    if (targetOs.isWindows) {
        val linkImportFile = linkJvmBindings.map { task ->
            task.outDir.get().asFile.walk().single { it.name.endsWith(".lib") }.absoluteFile
        }
        libImportFile.set(project.layout.file(linkImportFile))
    }

    val target = targetId(targetOs, targetArch)
    outDir.set(project.layout.buildDirectory.dir("maybe-signed-$target"))

    val toolsDir = project.layout.projectDirectory.dir("tools")
    if (targetOs == OS.Linux) {
        // Linux requires additional sealing to run on wider set of platforms.
        // See https://github.com/olonho/sealer.
        when (targetArch) {
            Arch.X64 -> sealer.set(toolsDir.file("sealer-x64"))
            Arch.Arm64 -> sealer.set(toolsDir.file("sealer-arm64"))
            else -> error("Unexpected combination of '$targetArch' and '$targetOs'")
        }
    }

    if (hostOs == OS.MacOS && this@maybeSignOrSealTask.skiko.isTeamcityCIBuild) {
        codesignClient.set(project.layout.buildDirectory.file(hostArch.darwinSignClientName))
    }
    signHost.set(skiko.signHost)
    signUser.set(skiko.signUser)
    signToken.set(skiko.signToken)
}

fun SkikoProjectContext.skikoJvmRuntimeJarTask(
    targetOs: OS,
    targetArch: Arch,
    awtJar: TaskProvider<Jar>,
    nativeFiles: List<Provider<File>>,
    libBaseName: String = "skiko"
) = project.registerSkikoTask<Jar>("skikoJvmRuntimeJar", targetOs, targetArch) {
    dependsOn(awtJar)
    val target = targetId(targetOs, targetArch)
    archiveBaseName.set(libBaseName)
    archiveClassifier.set(target)
    nativeFiles.forEach { provider -> from(provider) }
}

fun SkikoProjectContext.createJvmJar(
    os: OS,
    arch: Arch,
    commonJar: TaskProvider<Jar>,
    libBaseName: String,
    includeIcu: Boolean
): TaskProvider<Jar> = with(this.project) {
    val skiaBindingsDir = registerOrGetSkiaDirProvider(os, arch)
    val compileBindings = createCompileJvmBindingsTask(os, arch, skiaBindingsDir)
    val objcCompile = if (os == OS.MacOS) createObjcCompileTask(os, arch, skiaBindingsDir) else null
    val linkBindings = createLinkJvmBindings(
        os,
        arch,
        skiaBindingsDir,
        compileBindings,
        objcCompile,
        libBaseName
    )

    if (os.isMacOs) {
        createDownloadCodeSignClientDarwinTask(os, hostArch)
    }
    val maybeSign = maybeSignOrSealTask(os, arch, linkBindings)
    val nativeLib = maybeSign.map { it -> it.outputFiles.get().single { it.name.endsWith(os.dynamicLibExt) } }
    val createChecksums = createChecksumsTask(os, arch, nativeLib)
    val nativeFiles = mutableListOf(
        nativeLib,
        createChecksums.map { it.outputs.files.singleFile }
    )
    if (includeIcu && os == OS.Windows) {
        val target = targetId(os, arch)
        // Add ICU data files.
        nativeFiles.add(skiaBindingsDir.map { file(it.resolve("out/${buildType.id}-$target/icudtl.dat")) })
    }
    // For ARM macOS add x86 native code for compatibility.
    if (os == OS.MacOS && arch == Arch.Arm64) {
        val altArch = Arch.X64
        val skiaBindingsDir2 = registerOrGetSkiaDirProvider(os, altArch)
        val compileBindings2 = createCompileJvmBindingsTask(os, altArch, skiaBindingsDir2)
        val objcCompile2 = createObjcCompileTask(os, altArch, skiaBindingsDir2)
        val linkBindings2 = createLinkJvmBindings(
            os,
            altArch,
            skiaBindingsDir2,
            compileBindings2,
            objcCompile2,
            libBaseName
        )
        val maybeSign2 = maybeSignOrSealTask(os, altArch, linkBindings2)
        val nativeLib2 = maybeSign2.map { it.outputFiles.get().single() }
        val createChecksums2 = createChecksumsTask(os, altArch, nativeLib2)
        nativeFiles.add(nativeLib2)
        nativeFiles.add(createChecksums2.map { it.outputs.files.singleFile })
        allJvmRuntimeJars[os to altArch] = skikoJvmRuntimeJarTask(
            os,
            altArch,
            commonJar,
            nativeFiles,
            libBaseName
        )
    }

    val skikoJvmRuntimeJar = skikoJvmRuntimeJarTask(
        os,
        arch,
        commonJar,
        nativeFiles,
        libBaseName
    )

    allJvmRuntimeJars[os to arch] = skikoJvmRuntimeJar
    return skikoJvmRuntimeJar
}

fun SkikoProjectContext.createSkikoJvmJarTask(
    os: OS,
    arch: Arch,
    commonJar: TaskProvider<Jar>
): TaskProvider<Jar> =
    createJvmJar(
        os,
        arch,
        commonJar,
        libBaseName = "skiko",
        includeIcu = true
    )

fun SkikoProjectContext.createGraphiteSkikoJvmJarTask(
    os: OS,
    arch: Arch,
    commonJar: TaskProvider<Jar>
): TaskProvider<Jar> =
    createJvmJar(
        os,
        arch,
        commonJar,
        libBaseName = "skiko-graphite",
        includeIcu = false
    )

fun SkikoProjectContext.skikoRuntimeDirForTestsTask(
    targetOs: OS,
    targetArch: Arch,
    runtimeJars: List<Provider<Jar>>,
    additionalRuntimeLibraries: List<AdditionalRuntimeLibrary>,
) = project.registerSkikoTask<Copy>("skikoRuntimeDirForTests$${project.name}", targetOs, targetArch)  {
    dependsOn(runtimeJars)

    runtimeJars.forEach { jarProvider ->
        from(project.zipTree(jarProvider.flatMap { it.archiveFile }))
    }
    additionalRuntimeLibraries.forEach { lib ->
        from(project.zipTree(lib.jarTask.flatMap { it.archiveFile }))
    }
    duplicatesStrategy = DuplicatesStrategy.WARN
    destinationDir = project.layout.buildDirectory
        .dir("${project.name}-runtime-for-tests")
        .get()
        .asFile
}

fun SkikoProjectContext.skikoJarForTestsTask(
    runtimeDirForTestsTask: Provider<Copy>
) = project.registerSkikoTask<Jar>("skikoJvmJarForTests") {
    dependsOn(runtimeDirForTestsTask)
    from(runtimeDirForTestsTask.map { it.destinationDir })
    archiveFileName.set("skiko-runtime-for-tests.jar")
}

fun SkikoProjectContext.setupJvmTestTask(
    skikoAwtJarForTests: TaskProvider<Jar>,
    targetOs: OS,
    targetArch: Arch,
    extraRuntimeJars: List<TaskProvider<Jar>> = emptyList(),
    includeIcu: Boolean = true
) = with(project) {
    val skikoAwtRuntimeJarForTests = createJvmJar(targetOs, targetArch, skikoAwtJarForTests, libBaseName = project.name,
        includeIcu = includeIcu)
    val jars = listOf(skikoAwtRuntimeJarForTests) + extraRuntimeJars
    val skikoRuntimeDirForTests = skikoRuntimeDirForTestsTask(targetOs, targetArch, jars, additionalRuntimeLibraries)
    val skikoJarForTests = skikoJarForTestsTask(skikoRuntimeDirForTests)

    tasks.withType<Test>().configureEach {
        dependsOn(skikoRuntimeDirForTests)
        dependsOn(skikoJarForTests)
        options {
            val dir = skikoRuntimeDirForTests.map { it.destinationDir }.get()
            systemProperty("skiko.library.path", dir)
            val jar = skikoJarForTests.get().outputs.files.files.single { it.name.endsWith(".jar") }
            systemProperty("skiko.jar.path", jar.absolutePath)

            systemProperty("skiko.test.screenshots.dir", File(project.projectDir, "src/jvmTest/screenshots").absolutePath)
            systemProperty("skiko.test.font.dir", File(project.projectDir, "src/commonTest/resources/fonts").absolutePath)

            val testingOnCI = System.getProperty("skiko.test.onci", "false").toBoolean()
            val canRunPerformanceTests = testingOnCI
            val canRunUiTests = testingOnCI || System.getProperty("os.name") != "Mac OS X"
            systemProperty(
                "skiko.test.performance.enabled",
                System.getProperty("skiko.test.performance.enabled", canRunPerformanceTests.toString())
            )
            systemProperty("skiko.test.ui.enabled", System.getProperty("skiko.test.ui.enabled", canRunUiTests.toString()))
            systemProperty("skiko.test.ui.renderApi", System.getProperty("skiko.test.ui.renderApi", "all"))
            systemProperty("skiko.test.ui.renderApi.ignoreAssertsFor", System.getProperty("skiko.test.ui.renderApi.ignoreAssertsFor", "OPENGL"))
            systemProperty("skiko.test.debug", buildType == SkiaBuildType.DEBUG)

            // Tests should be deterministic, so disable scaling.
            // On MacOs we need the actual scale, otherwise we will have aliased screenshots because of scaling.
            if (System.getProperty("os.name") != "Mac OS X") {
                systemProperty("sun.java2d.dpiaware", "false")
                systemProperty("sun.java2d.uiScale", "1")
            }
        }

        classpath += files(skikoAwtRuntimeJarForTests)
        jvmArgs = listOf("--add-opens", "java.desktop/sun.font=ALL-UNNAMED")
        testLogging {
            events("PASSED", "FAILED", "SKIPPED")
            showStandardStreams = true
            showExceptions = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

fun Project.androidHomePath(): Provider<String> {
    val androidHomeFromSdkHome: Provider<String> =
        project.providers.environmentVariable("ANDROID_HOME")

    // ANDROID_SDK_ROOT name is deprecated in favor of ANDROID_HOME
    val deprecatedAndroidHomeFromSdkRoot: Provider<String> =
        project.providers.environmentVariable("ANDROID_SDK_ROOT")

    val androidHomeFromUserHome: Provider<String> =
        project.providers.systemProperty("user.home")
            .map { userHome ->
                listOf("Library/Android/sdk", ".android/sdk", "Android/sdk")
                    .map { "$userHome/$it" }
                    .firstOrNull { File(it).exists() }
                    ?: error("Define Android SDK via ANDROID_SDK_ROOT")
            }
    return androidHomeFromSdkHome
        .orElse(deprecatedAndroidHomeFromSdkRoot)
        .orElse(androidHomeFromUserHome)
}
