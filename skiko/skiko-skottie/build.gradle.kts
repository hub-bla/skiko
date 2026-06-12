@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.crypto.checksum.Checksum
import org.jetbrains.compose.internal.publishing.MavenCentralProperties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.kotlin.dsl.withType
import org.gradle.kotlin.dsl.named
import tasks.configuration.*
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import dsl.SkikoDependencyScope

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library") apply false
    org.jetbrains.dokka
    `maven-publish`
    signing
    org.gradle.crypto.checksum
    org.jetbrains.kotlinx.benchmark
}

if (supportAndroid) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

apply<SideWasmImportsGeneratorPlugin>()

// TODO: investigate this
val skiko = SkikoProperties(rootProject)
val skikoSkottieArtifacts = SkikoArtifacts(
    artifactIdPrefix = "skiko-skottie",
    displayName = "Skiko Skottie",
    pomDescription = "Kotlin Skia Skottie bindings",
)
val buildType = skiko.buildType
val targetOs = hostOs
val targetArch = skiko.targetArch

val coreDependencies: SkikoDependencyScope.() -> Unit = {
    dependsOnCore()
    targets {
        all {
            staticSkiaLibs(
                "skottie",
                "sksg",
                "jsonreader"
            )
        }
        wasm {
            linkFlags(
                "-s", "SIDE_MODULE=2",
            )
        }
    }
}
val skikoSkottieProjectContext = SkikoProjectContext(
    project = project,
    skiko = skiko,
    kotlin = kotlin,
    kind = SkikoModuleKind.EXTENSION,
    artifacts = skikoSkottieArtifacts,
    windowsSdkPathProvider = {
        findWindowsSdkPaths(gradle, targetArch)
    },
    createChecksumsTask = { targetOs: OS, targetArch: Arch, fileToChecksum: Provider<File> ->
        createChecksumsTask(targetOs, targetArch, fileToChecksum)
    },
    additionalRuntimeLibraries = project.registerAdditionalLibraries(targetOs, targetArch, skiko, skikoSkottieArtifacts),
    configureDependencies = coreDependencies
)
extensions.add(SKIKO_PROJECT_CONTEXT_EXTENSION_NAME, skikoSkottieProjectContext)

repositories {
    mavenCentral()
    google()
}

kotlin {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.add("-opt-in=org.jetbrains.skiko.InternalSkikoApi")
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
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            namespace = "org.jetbrains.skiko"
            compileSdk = 35
            minSdk = 24
            withJava()
            withHostTest {}

            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }

    if (supportWeb) {
        skikoSkottieProjectContext.declareWasmTasks(
            extraIncludeDirs = listOf(
                rootProject.projectDir.resolve("src/nativeJsMain/cpp"),
                rootProject.projectDir.resolve("src/commonMain/cpp/common/include")
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
                    rootProject.tasks.named("compileKotlinJs"),
                    rootProject.tasks.named("compileKotlinWasmJs"),
                    rootProject.tasks.named("compileTestKotlinJs"),
                    rootProject.tasks.named("compileTestKotlinWasmJs"),
                )
            }

            setupImportsGeneratorPlugin(skikoSkottieArtifacts.artifactIdPrefix, isSideModule = skikoSkottieProjectContext.kind == SkikoModuleKind.EXTENSION)
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
                    rootProject.tasks.named("compileKotlinJs"),
                    rootProject.tasks.named("compileKotlinWasmJs"),
                    rootProject.tasks.named("compileTestKotlinJs"),
                    rootProject.tasks.named("compileTestKotlinWasmJs"),
                )
            }

            setupImportsGeneratorPlugin(skikoSkottieArtifacts.artifactIdPrefix, isSideModule = true)
        }
    }

    if (supportNativeMac) {
        skikoSkottieProjectContext.configureNativeTarget(OS.MacOS, Arch.X64, macosX64())
        skikoSkottieProjectContext.configureNativeTarget(OS.MacOS, Arch.Arm64, macosArm64())
    }

    if (supportNativeLinux) {
        skikoSkottieProjectContext.configureNativeTarget(OS.Linux, Arch.X64, linuxX64())
        skikoSkottieProjectContext.configureNativeTarget(OS.Linux, Arch.Arm64, linuxArm64())
    }

    if (supportNativeIosArm64) {
        skikoSkottieProjectContext.configureNativeTarget(OS.IOS, Arch.Arm64, iosArm64())
    }

    if (supportNativeIosSimulatorArm64) {
        skikoSkottieProjectContext.configureNativeTarget(OS.IOS, Arch.Arm64, iosSimulatorArm64())
    }

    if (supportNativeIosX64) {
        skikoSkottieProjectContext.configureNativeTarget(OS.IOS, Arch.X64, iosX64())
    }

    if (supportNativeTvosArm64) {
        skikoSkottieProjectContext.configureNativeTarget(OS.TVOS, Arch.Arm64, tvosArm64())
    }

    if (supportNativeTvosSimulatorArm64) {
        skikoSkottieProjectContext.configureNativeTarget(OS.TVOS, Arch.Arm64, tvosSimulatorArm64())
    }

    if (supportNativeTvosX64) {
        skikoSkottieProjectContext.configureNativeTarget(OS.TVOS, Arch.X64, tvosX64())
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
        implementation(project(":test-utils"))
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

    if (supportAndroid && supportAwt) {
        sourceSets.named("androidMain") {
            dependsOn(sourceSets.getByName("jvmMain"))
        }
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
    val os = OS.Android
    kotlin.targets.getByName("android").generateVersion(os, Arch.Arm64, skiko)
    val skikoSkottieAndroidArtifact by project.tasks.registering(Jar::class) {
        archiveBaseName.set("skiko-skottie-android")
        from(kotlin.targets.getByName("android").compilations.getByName("main").output.allOutputs)
    }
    for (arch in arrayOf(Arch.X64, Arch.Arm64)) {
        skikoSkottieProjectContext.createSkikoJvmJarTask(os, arch, skikoSkottieAndroidArtifact)
    }

    tasks.withType<JavaCompile>().configureEach {
        if (name.startsWith("compileAndroid") && name.endsWith("JavaWithJavac")) {
            sourceCompatibility = JavaVersion.VERSION_11.toString()
            targetCompatibility = JavaVersion.VERSION_11.toString()
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

if (supportAwt) {
    val skikoSkottieAwtJarForTests by project.tasks.registering(Jar::class) {
        archiveBaseName.set("skiko-skottie-awt-test")
        from(kotlin.jvm("awt").compilations["main"].output.allOutputs)
    }
    skikoSkottieProjectContext.setupJvmTestTask(skikoSkottieAwtJarForTests, targetOs, targetArch)
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
