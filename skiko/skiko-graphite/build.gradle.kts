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

val skikoGraphiteContext = SkikoProjectContext(
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
    skikoGraphiteContext.declareSkiaTasks()

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
        skikoGraphiteContext.declareWasmTasks()

        js {
            outputModuleName.set("skiko-graphite-kjs") // override the name to avoid name collision with a different skiko.js file
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
            outputModuleName.set("skiko-graphite-kjs-wasm") // override the name to avoid name collision with a different skiko.js file
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
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.MacOS, Arch.X64, macosX64())
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.MacOS, Arch.Arm64, macosArm64())
    }
    if (supportNativeLinux) {
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.Linux, Arch.X64, linuxX64())
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.Linux, Arch.Arm64, linuxArm64())
    }
    if (supportNativeIosArm64) {
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.IOS, Arch.Arm64, iosArm64())
    }
    if (supportNativeIosSimulatorArm64) {
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.IOS, Arch.Arm64, iosSimulatorArm64())
    }
    if (supportNativeIosX64) {
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.IOS, Arch.X64, iosX64())
    }
    if (supportNativeTvosArm64) {
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.TVOS, Arch.Arm64, tvosArm64())
    }
    if (supportNativeTvosSimulatorArm64) {
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.TVOS, Arch.Arm64, tvosSimulatorArm64())
    }
    if (supportNativeTvosX64) {
        skikoGraphiteContext.configureGraphiteNativeTarget(OS.TVOS, Arch.X64, tvosX64())
    }

    sourceSets.commonMain.dependencies {
        implementation(kotlin("stdlib"))
        compileOnly(project(":"))
    }

    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation(kotlin("test-annotations-common"))
    }

    skikoGraphiteContext.jvmMainSourceSet?.dependencies {
        implementation(kotlin("stdlib"))
    }

    skikoGraphiteContext.webMainSourceSet?.dependencies {
        implementation(libs.kotlinx.browser)
    }

    skikoGraphiteContext.awtMainSourceSet?.dependencies {
        implementation(libs.jetbrainsRuntime.api)
    }

    skikoGraphiteContext.androidMainSourceSet?.dependencies {
        implementation(libs.coroutines.android)
    }

    skikoGraphiteContext.jvmTestSourceSet?.dependencies {
        implementation(kotlin("test-junit"))
        implementation(kotlin("test"))
    }

    skikoGraphiteContext.wasmJsTest?.dependencies {
        implementation(kotlin("test-wasm-js"))
    }

    skikoGraphiteContext.webTestSourceSet?.apply {
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
        namespace = "org.jetbrains.skiko.graphite"
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
    val skikoGraphiteAndroidJar by project.tasks.registering(Jar::class) {
        archiveBaseName.set("skiko-graphite-android")
        from(kotlin.androidTarget("android").compilations["release"].output.allOutputs)
    }
    for (arch in arrayOf(Arch.X64, Arch.Arm64)) {
        skikoGraphiteContext.createSkikoJvmJarTask(os, arch, skikoGraphiteAndroidJar)
    }
    tasks.matching { name == "publishAndroidReleasePublicationToMavenLocal" }.configureEach {
        dependsOn(skikoGraphiteAndroidJar)
    }
    tasks.matching { name == "generateMetadataFileForAndroidReleasePublication" }.configureEach {
        dependsOn(skikoGraphiteAndroidJar)
    }
}


if (supportAwt) {
    val skikoGraphiteAwtJar by project.tasks.registering(Jar::class) {
        archiveBaseName.set("skiko-graphite-awt")
        from(kotlin.jvm("awt").compilations["main"].output.allOutputs)
    }
    skikoGraphiteContext.createGraphiteSkikoJvmJarTask(targetOs, targetArch, skikoGraphiteAwtJar)
    afterEvaluate {
        tasks.matching { it.name == "publishAwtPublicationToMavenLocal" }.configureEach {
            dependsOn(skikoGraphiteAwtJar)
        }
        tasks.matching { it.name == "generateMetadataFileForAwtPublication" }.configureEach {
            dependsOn(skikoGraphiteAwtJar)
        }
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

afterEvaluate {
    tasks.configureEach {
        if (group == "publishing") {
            val allRepositories = publishing.repositories.map { it.name } + "MavenLocal"
            val publishToTasks = allRepositories.map { "publishTo$it" }
            if (name != "publish" && name !in publishToTasks) {
                group = "other publishing"
            }
        }
    }
}

skikoGraphiteContext.declarePublications("graphite")

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
