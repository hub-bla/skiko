@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.jetbrains.compose.internal.publishing.MavenCentralProperties
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import tasks.configuration.*

plugins {
    kotlin("multiplatform")
    `maven-publish`
    signing
    org.gradle.crypto.checksum
    org.jetbrains.kotlinx.benchmark
}

if (supportAndroid) {
    apply<LibraryPlugin>()
}

apply<WasmImportsGeneratorCompilerPluginSupportPlugin>()
apply<WasmImportsGeneratorForTestCompilerPluginSupportPlugin>()

val skiko = SkikoProperties(rootProject)
val buildType = skiko.buildType
val targetOs = hostOs
val targetArch = skiko.targetArch

fun skiaSkottieStaticLibraries(skiaDir: String, targetString: String, buildType: SkiaBuildType): List<String> {
    val skiaBinSubdir = "$skiaDir/out/${buildType.id}-$targetString"
    return listOf("$skiaBinSubdir/libskottie.a", "$skiaBinSubdir/libsksg.a")
}

val skikoSkottieProjectContext = SkikoProjectContext(
    project = project,
    skiko = skiko,
    kotlin = kotlin,
    windowsSdkPathProvider = {
        findWindowsSdkPaths(gradle, targetArch)
    },
    createChecksumsTask = { targetOs: OS, targetArch: Arch, fileToChecksum: Provider<File> ->
        createChecksumsTask(targetOs, targetArch, fileToChecksum)
    },
    additionalRuntimeLibraries = emptyList()
)

repositories {
    mavenCentral()
    google()
}

kotlin {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }

    applyHierarchyTemplate(skikoSourceSetHierarchyTemplate)
    skikoSkottieProjectContext.declareSkiaTasks()

    if (supportAwt) {
        jvm("awt") {
            compilations.all {
                compileTaskProvider.configure {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
            generateVersion(targetOs, targetArch, skiko)
        }
    }

    if (supportAndroid) {
        androidTarget("android") {
            publishLibraryVariants("release")
            compilations.all {
                compileTaskProvider.configure {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
            // Keep the previously defined attribute that was used to distinguish JVM and android variant
            attributes {
                attributes.attribute(Attribute.of("ui", String::class.java), "android")
            }
            // TODO: seems incorrect.
            generateVersion(OS.Android, Arch.Arm64, skiko, "release")
        }
    }

    if (supportWeb) {
        skikoSkottieProjectContext.declareWasmTasks()

        js {
            outputModuleName.set("skiko-skottie-kjs") // override the name to avoid name collision with a different skiko.js file
            browser {
                testTask {
                    useKarma {
                        useChromeHeadless()
                        useConfigDirectory(project.projectDir.resolve("karma.config.d").resolve("js"))
                    }
                }
            }
            binaries.executable()
            generateVersion(OS.Wasm, Arch.Wasm, skiko)

            val test by compilations.getting
            project.tasks.named<Copy>(test.processResourcesTaskName) {
                dependsOn(test.compileTaskProvider, tasks["compileTestKotlinWasmJs"])
            }

            setupImportsGeneratorPlugin()
        }

        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
            outputModuleName.set("skiko-skottie-kjs-wasm") // override the name to avoid name collision with a different skiko.js file
            browser {
                testTask {
                    useKarma {
                        useChromeHeadless()
                        useConfigDirectory(project.projectDir.resolve("karma.config.d").resolve("wasm"))
                    }
                }
            }
            generateVersion(OS.Wasm, Arch.Wasm, skiko)

            val test by compilations.getting
            project.tasks.named<Copy>(test.processResourcesTaskName) {
                dependsOn(test.compileTaskProvider, tasks["compileTestKotlinJs"])
            }

            setupImportsGeneratorPlugin()
        }
    }

    if (supportNativeMac) {
        skikoSkottieProjectContext.configureNativeTarget(
            OS.MacOS,
            Arch.X64,
            macosX64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries
        )
        skikoSkottieProjectContext.configureNativeTarget(
            OS.MacOS,
            Arch.Arm64,
            macosArm64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries
        )
    }

    if (supportNativeLinux) {
        skikoSkottieProjectContext.configureNativeTarget(
            OS.Linux,
            Arch.X64,
            linuxX64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries,
            extraLinuxOptions = { skiaBinDir, _ ->
                listOf("$skiaBinDir/libskottie.a")
            }
        )
        skikoSkottieProjectContext.configureNativeTarget(
            OS.Linux,
            Arch.Arm64,
            linuxArm64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries,
            extraLinuxOptions = { skiaBinDir, _ ->
                listOf("$skiaBinDir/libskottie.a")
            }
        )
    }

    if (supportNativeIosArm64) {
        skikoSkottieProjectContext.configureNativeTarget(
            OS.IOS,
            Arch.Arm64,
            iosArm64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries
        )
    }

    if (supportNativeIosSimulatorArm64) {
        skikoSkottieProjectContext.configureNativeTarget(
            OS.IOS,
            Arch.Arm64,
            iosSimulatorArm64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries
        )
    }

    if (supportNativeIosX64) {
        skikoSkottieProjectContext.configureNativeTarget(
            OS.IOS,
            Arch.X64,
            iosX64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries
        )
    }

    if (supportNativeTvosArm64) {
        skikoSkottieProjectContext.configureNativeTarget(
            OS.TVOS,
            Arch.Arm64,
            tvosArm64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries
        )
    }

    if (supportNativeTvosSimulatorArm64) {
        skikoSkottieProjectContext.configureNativeTarget(
            OS.TVOS,
            Arch.Arm64,
            tvosSimulatorArm64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries
        )
    }

    if (supportNativeTvosX64) {
        skikoSkottieProjectContext.configureNativeTarget(
            OS.TVOS,
            Arch.X64,
            tvosX64(),
            libPrefix = "skiko-skottie-native-bridges",
            cinteropNameProvider = { "skiko-skottie" },
            librariesProvider = ::skiaSkottieStaticLibraries
        )
    }

    sourceSets.commonMain.dependencies {
        implementation(kotlin("stdlib"))
        /*
        We use compileOnly here because the root project publishes multiple artifacts
        which makes api/implementation(project(":")) fail during publishing.
        This avoids Gradle's multi-publication ambiguity but skiko core is NOT added
        as a transitive dependency of skiko-skottie, and it will NOT appear in the published POM
        consumers MUST explicitly depend on both:
            - implementation("org.jetbrains.skiko:skiko-x")
            - implementation("org.jetbrains.skiko:skiko-skottie-x")
         */
        compileOnly(project(":"))
    }

    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation(kotlin("test-annotations-common"))
        implementation(project(":"))
    }

    skikoSkottieProjectContext.jvmMainSourceSet?.dependencies {
        implementation(kotlin("stdlib"))
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation(kotlin("test-annotations-common"))
    }
    skikoSkottieProjectContext.jvmTestSourceSet?.dependencies {
        implementation(libs.coroutines.test)
        implementation(kotlin("test-junit"))
        implementation(kotlin("test"))
    }
    skikoSkottieProjectContext.awtTestSourceSet?.dependencies {
        implementation(libs.kotlinx.benchmark.runtime)
    }
    skikoSkottieProjectContext.webMainSourceSet?.dependencies {
        implementation(libs.kotlinx.browser)
    }

    skikoSkottieProjectContext.awtMainSourceSet?.dependencies {
        implementation(libs.jetbrainsRuntime.api)
    }

    skikoSkottieProjectContext.androidMainSourceSet?.dependencies {
        implementation(libs.coroutines.android)
    }

    skikoSkottieProjectContext.jvmTestSourceSet?.dependencies {
        implementation(kotlin("test-junit"))
        implementation(kotlin("test"))
    }

    skikoSkottieProjectContext.wasmJsTest?.dependencies {
        implementation(kotlin("test-wasm-js"))
    }

    skikoSkottieProjectContext.webTestSourceSet?.apply {
        resources.srcDirs(
            tasks.named("linkWasm"), wasmImports
        )
    }

    if (supportAnyNative) {
        sourceSets.all {
            // Really ugly, see https://youtrack.jetbrains.com/issue/KT-46649 why it is required,
            // note that setting it per source set still keeps it unset in commonized source sets.
            languageSettings.optIn("kotlin.native.SymbolNameIsInternal")
        }
        configureIOSTestsWithMetal(project)
    }
}

if (supportAndroid) {
    // Android configuration, when available
    configure<LibraryExtension> {
        compileSdk = 33
        namespace = "org.jetbrains.skia.skottie"
        defaultConfig.minSdk = 24
        defaultConfig.targetSdk = 24
        compileOptions.sourceCompatibility = JavaVersion.VERSION_1_8
        compileOptions.targetCompatibility = JavaVersion.VERSION_1_8
        sourceSets.named("main") {
            java.srcDirs("src/androidMain/java")
            res.srcDirs("src/androidMain/res")
        }
    }

    val os = OS.Android
    val skikoSkottieAndroidJar by project.tasks.registering(Jar::class) {
        archiveBaseName.set("skiko-skottie-android")
        from(kotlin.androidTarget("android").compilations["release"].output.allOutputs)
    }
    for (arch in arrayOf(Arch.X64, Arch.Arm64)) {
        skikoSkottieProjectContext.createJvmJar(os, arch, skikoSkottieAndroidJar,
            libBaseName = "skiko-skottie",
            includeIcu = false)
    }
    tasks.matching { name == "publishAndroidReleasePublicationToMavenLocal" }.configureEach {
        // It needs to be compatible with Gradle 8.1
        dependsOn(skikoSkottieAndroidJar)
    }
    tasks.matching { name == "generateMetadataFileForAndroidReleasePublication" }.configureEach {
        // It needs to be compatible with Gradle 8.1
        dependsOn(skikoSkottieAndroidJar)
    }
}

// TODO now it can be moved, move it if you change this
// Can't be moved to buildSrc because of Checksum dependency
fun createChecksumsTask(
    targetOs: OS,
    targetArch: Arch,
    fileToChecksum: Provider<File>
) = project.registerSkikoTask<org.gradle.crypto.checksum.Checksum>("createChecksums", targetOs, targetArch) {
    inputFiles = project.files(fileToChecksum)
    checksumAlgorithm = org.gradle.crypto.checksum.Checksum.Algorithm.SHA256
    outputDirectory = layout.buildDirectory.dir("checksums-${targetId(targetOs, targetArch)}")
}

if (supportAwt) {
    val targetSuffix = joinToTitleCamelCase(targetOs.id, targetArch.id)

    val skikoSkottieAwtJarForTests by project.tasks.registering(Jar::class) {
        archiveBaseName.set("skiko-skottie-awt-test")
        from(kotlin.jvm("awt").compilations["main"].output.allOutputs)
    }
    val rootRuntimeJar = project(":").tasks.named<Jar>("skikoJvmRuntimeJar$targetSuffix")

    skikoSkottieProjectContext.setupJvmTestTask(skikoSkottieAwtJarForTests, targetOs, targetArch, extraRuntimeJars=listOf(rootRuntimeJar))
}

afterEvaluate {
    tasks.configureEach {
        if (group == "publishing") {
            // There are many intermediate tasks in 'publishing' group.
            // There are a lot of them and they have verbose names.
            // To decrease noise in './gradlew tasks' output and Intellij Gradle tool window,
            // group verbose tasks in a separate group 'other publishing'.
            val allRepositories = publishing.repositories.map { it.name } + "MavenLocal"
            val publishToTasks = allRepositories.map { "publishTo$it" }
            if (name != "publish" && name !in publishToTasks) {
                group = "other publishing"
            }
        }
    }
}

skikoSkottieProjectContext.declarePublications("skottie")

val mavenCentral = MavenCentralProperties(project)
if (skiko.isTeamcityCIBuild || mavenCentral.signArtifacts) {
    signing {
        sign(publishing.publications)
        useInMemoryPgpKeys(mavenCentral.signArtifactsKey.get(), mavenCentral.signArtifactsPassword.get())
    }
    configureSignAndPublishDependencies()
}

tasks.withType<KotlinNativeCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
}
