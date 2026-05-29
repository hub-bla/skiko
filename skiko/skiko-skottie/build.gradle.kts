@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
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

apply<SideWasmImportsGeneratorPlugin>()

val skiko = SkikoProperties(rootProject)
val skikoSkottieArtifacts = SkikoArtifacts(
    artifactIdPrefix = "skiko-skottie",
    displayName = "Skiko Skottie",
)
val buildType = skiko.buildType
val targetOs = hostOs
val targetArch = skiko.targetArch
val skikoSkottieModule = project.currentSkikoExtensionModule()

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
    additionalRuntimeLibraries = emptyList(),
    artifacts = skikoSkottieArtifacts,
)

fun configureSkottieNativeTarget(os: OS, arch: Arch, target: KotlinNativeTarget) {
    skikoSkottieProjectContext.configureNativeBridgesForExtension(
        skikoSkottieModule,
        os,
        arch,
        target
    )
}

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

    if (supportAwt) {
        jvm("awt") {
            compilations.all {
                compileTaskProvider.configure {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
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
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
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
        val skiaWasmDir = skikoSkottieProjectContext.registerOrGetSkiaDirProvider(OS.Wasm, Arch.Wasm, false)

        skikoSkottieProjectContext.declareWasmTasks(
            isSideModule = true,
            extraIncludeDirs = listOf(
                project(":").projectDir.resolve("src/nativeJsMain/cpp"),
                project(":").projectDir.resolve("src/commonMain/cpp/common/include")
            )
        )

        js {
            outputModuleName.set("skiko-skottie-kjs")
            browser {
                testTask {
                    useKarma {
                        useChromeHeadless()
                        useConfigDirectory(rootProject.projectDir.resolve("karma.config.d").resolve("js"))
                    }
                }
            }
            binaries.executable()
            generateVersion(OS.Wasm, Arch.Wasm, skiko)

            val test by compilations.getting
            project.tasks.named<Copy>(test.processResourcesTaskName) {
                dependsOn(
                    test.compileTaskProvider,
                    tasks["compileTestKotlinWasmJs"],
                    project(":").tasks.named("compileKotlinJs"),
                    project(":").tasks.named("compileKotlinWasmJs"),
                    project(":").tasks.named("compileTestKotlinJs"),
                    project(":").tasks.named("compileTestKotlinWasmJs"),
                )
            }

            setupImportsGeneratorPlugin(isSideModule = true)
        }

        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
            outputModuleName.set("skiko-skottie-kjs-wasm")
            browser {
                testTask {
                    useKarma {
                        useChromeHeadless()
                        useConfigDirectory(rootProject.projectDir.resolve("karma.config.d").resolve("wasm"))
                    }
                }
            }
            generateVersion(OS.Wasm, Arch.Wasm, skiko)

            val test by compilations.getting
            project.tasks.named<Copy>(test.processResourcesTaskName) {
                dependsOn(
                    test.compileTaskProvider,
                    tasks["compileTestKotlinJs"],
                    project(":").tasks.named("compileKotlinJs"),
                    project(":").tasks.named("compileKotlinWasmJs"),
                    project(":").tasks.named("compileTestKotlinJs"),
                    project(":").tasks.named("compileTestKotlinWasmJs"),
                )
            }

            setupImportsGeneratorPlugin(isSideModule = true)
        }
    }

    if (supportNativeMac) {
        configureSkottieNativeTarget(OS.MacOS, Arch.X64, macosX64())
        configureSkottieNativeTarget(OS.MacOS, Arch.Arm64, macosArm64())
    }

    if (supportNativeLinux) {
        configureSkottieNativeTarget(OS.Linux, Arch.X64, linuxX64())
        configureSkottieNativeTarget(OS.Linux, Arch.Arm64, linuxArm64())
    }

    if (supportNativeIosArm64) {
        configureSkottieNativeTarget(OS.IOS, Arch.Arm64, iosArm64())
    }

    if (supportNativeIosSimulatorArm64) {
        configureSkottieNativeTarget(OS.IOS, Arch.Arm64, iosSimulatorArm64())
    }

    if (supportNativeIosX64) {
        configureSkottieNativeTarget(OS.IOS, Arch.X64, iosX64())
    }

    if (supportNativeTvosArm64) {
        configureSkottieNativeTarget(OS.TVOS, Arch.Arm64, tvosArm64())
    }

    if (supportNativeTvosSimulatorArm64) {
        configureSkottieNativeTarget(OS.TVOS, Arch.Arm64, tvosSimulatorArm64())
    }

    if (supportNativeTvosX64) {
        configureSkottieNativeTarget(OS.TVOS, Arch.X64, tvosX64())
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

    skikoSkottieProjectContext.wasmJsTest?.dependencies {
        implementation(kotlin("test-wasm-js"))
    }
    skikoSkottieProjectContext.webTestSourceSet?.dependencies {
        implementation(libs.coroutines.core)
    }

    skikoSkottieProjectContext.webTestSourceSet?.apply {
        resources.srcDirs(
            tasks.named("linkWasm"),
            project(":").tasks.named("linkWasm"),
            wasmImports,
            project(":").wasmImports,
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
        compileOptions.sourceCompatibility = JavaVersion.VERSION_11
        compileOptions.targetCompatibility = JavaVersion.VERSION_11
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
            libBaseName = skikoSkottieModule.libBaseName,
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

skikoSkottieProjectContext.declarePublications()

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
