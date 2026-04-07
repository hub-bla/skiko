import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.publish.PublicationContainer
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.attributes.KlibPackaging
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinUsages
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import tasks.configuration.isUikitSimulator

/**
 * Custom Gradle attribute used to disambiguate JVM runtime JAR variants by GPU backend.
 *
 * Published variant ids:
 *   - "ganesh"   → Ganesh backend
 *   - "graphite" → Graphite backend (Dawn or native — resolved internally per target OS)
 *   - "all"      → Fat variant bundling all enabled backends
 *
 * The internal split between graphite-dawn and graphite-native is a build detail
 * and is never exposed at this level.
 *
 * Consumers select a variant:
 * ```kotlin
 * configurations.runtimeClasspath {
 *     attributes {
 *         attribute(SkikoGpuBackendAttribute.ATTRIBUTE, objects.named("ganesh"))
 *     }
 * }
 * ```
 */
interface SkikoGpuBackendAttribute : Named {
    companion object {
        @JvmField
        val ATTRIBUTE: Attribute<SkikoGpuBackendAttribute> =
            Attribute.of("org.jetbrains.skiko.gpu-backend", SkikoGpuBackendAttribute::class.java)
    }
}

private val SkikoProjectContext.publishing get() = project.extensions.getByType(PublishingExtension::class.java)

private val awtRuntimeTargets = listOf(
    OS.MacOS to Arch.X64, OS.MacOS to Arch.Arm64,
    OS.Linux to Arch.X64, OS.Linux to Arch.Arm64,
    OS.Windows to Arch.X64, OS.Windows to Arch.Arm64
)

private class SkikoPublishingContext(
    val projectContext: SkikoProjectContext,
) {
    val project = projectContext.project
    val kotlin = projectContext.kotlin
    val skiko = projectContext.skiko
    val additionalRuntimeLibraries = projectContext.additionalRuntimeLibraries

    val pomNameForPublication: MutableMap<String, String> = HashMap()

    fun publishing(configure: PublishingExtension.() -> Unit) {
        projectContext.publishing.apply(configure)
    }

    fun publications(configure: PublicationContainer.() -> Unit) {
        projectContext.publishing.publications.apply(configure)
    }
}

fun SkikoProjectContext.declarePublications() {
    val ctx = SkikoPublishingContext(this)
    ctx.configurePublishingRepositories()
    ctx.configurePublicationDefaults()
    ctx.configureAllJvmRuntimeJarPublications()
    ctx.configureNativeCinteropPublications()
    ctx.configureAwtRuntimeJarPublication()
    ctx.configureAwtPublicationConstraints()
    ctx.configureAdditionalRuntimeLibrariesPublication()
    ctx.configureWebPublication()
    ctx.configureAndroidPublication()

    ctx.configurePomNames()
}

private val SkikoPublishingContext.emptySourcesJar
    get() = project.tasks.registerOrGetTask<Jar>("emptySourcesJar") {
        archiveClassifier.set("sources")
    }

private val SkikoPublishingContext.emptyJavadocJar
    get() = project.tasks.registerOrGetTask<Jar>("emptyJavadocJar") {
        archiveClassifier.set("javadoc")
    }

private fun SkikoPublishingContext.configurePublishingRepositories() {
    publishing {
        repositories {
            configureEach {
                val repoName = name
                project.tasks.register("publishTo${repoName}") {
                    group = "publishing"
                    dependsOn(project.tasks.named("publishAllPublicationsTo${repoName}Repository"))
                }
            }
            maven {
                name = "BuildRepo"
                url = project.rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
            }
            maven {
                name = "ComposeRepo"
                url = project.uri(skiko.composeRepoUrl)
                credentials {
                    username = skiko.composeRepoUserName
                    password = skiko.composeRepoKey
                }
            }
        }
    }
}

private fun SkikoPublishingContext.configurePublicationDefaults() {
    pomNameForPublication["kotlinMultiplatform"] = "Skiko KMP"
    kotlin.targets.forEach {
        pomNameForPublication[it.name] = "Skiko ${toTitleCase(it.name)}"
    }

    publishing {
        publications.configureEach {
            this as MavenPublication
            groupId = SkikoArtifacts.groupId

            // Necessary for publishing to Maven Central
            artifact(emptyJavadocJar)

            pom {
                description.set("Kotlin Skia bindings")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                val repoUrl = "https://www.github.com/JetBrains/skiko"
                url.set(repoUrl)
                scm {
                    url.set(repoUrl)
                    val repoConnection = "scm:git:$repoUrl.git"
                    connection.set(repoConnection)
                    developerConnection.set(repoConnection)
                }
                developers {
                    developer {
                        name.set("Compose Multiplatform Team")
                        organization.set("JetBrains")
                        organizationUrl.set("https://www.jetbrains.com")
                    }
                }
            }
        }
    }
}

private fun SkikoPublishingContext.configureAllJvmRuntimeJarPublications() = publications {
    val byPlatform =
        projectContext.allJvmRuntimeJars
            .entries
            .groupBy { (triple, _) -> triple.first to triple.second }
            .mapValues { (_, entries) -> entries.associate { (triple, jar) -> triple.third to jar } }

    byPlatform.forEach { (platform, variantJars) ->
        val (os, arch) = platform

        create("skikoJvmRuntime${toTitleCase(os.id)}${toTitleCase(arch.id)}", MavenPublication::class.java) {
            pomNameForPublication[name] = "Skiko JVM Runtime for ${os.name} ${arch.name}"
            artifactId = SkikoArtifacts.jvmRuntimeArtifactIdFor(os, arch)

            val component = project.serviceOf<SoftwareComponentFactory>().adhoc(
                "skikoJvmRuntime${toTitleCase(os.id)}${toTitleCase(arch.id)}Component"
            )

            variantJars.forEach { (backend, jarTask) ->
                val variantId = backend.id

                // Attributed variant — for consumers who explicitly request a backend.
                val attributedConfig = project.configurations.create("skikoJvmRuntimeElements-${targetId(os, arch)}-$variantId").apply {
                    isCanBeResolved = false
                    isCanBeConsumed = true

                    attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                    attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
                    attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
                    attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements.JAR))
                    attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
                    attributes.attribute(
                        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                        project.objects.named(TargetJvmEnvironment.STANDARD_JVM)
                    )

                    attributes.attribute(
                        SkikoGpuBackendAttribute.ATTRIBUTE,
                        project.objects.named(SkikoGpuBackendAttribute::class.java, variantId)
                    )

                    outgoing.artifact(jarTask.flatMap { it.archiveFile })
                    outgoing.artifact(emptySourcesJar.flatMap { it.archiveFile }) {
                        classifier = "sources"
                    }

                    dependencies.add(
                        project.dependencies.create(
                            SkikoArtifacts.groupId,
                            SkikoArtifacts.jvmArtifactId,
                            "[${skiko.deployVersion}]"
                        )
                    )
                }
                component.addVariantsFromConfiguration(attributedConfig) {
                    mapToMavenScope("runtime")
                }
            }

            from(component)

            /*
            The entire machinery only works with Gradle attributes;
            therefore, we do not add any dependencies to the maven pom file
             */
            pom.withXml {
                val deps = asElement().getElementsByTagName("dependencies")
                for (i in 0 until deps.length) deps.item(i).parentNode.removeChild(deps.item(i))
            }
        }
    }
}

/**
 * There are several artifacts, providing the native runtime, for each OS and architecture:
 * - skiko-awt-runtime-macos-arm64
 * - skiko-awt-runtime-macos-x64
 * - ...
 *
 * Each of those artifacts gets published using its own maven coordinates.
 * In order to support consumers who would like to express a single dependency on Skiko, this 'uber' publication is created,
 * listing each OS and architecture-specific artifact as a dependency to a Gradle variant, distinguised by
 * default Gradle attributes.
 *
 * ```
 * org.jetbrains.skiko:skiko-awt-runtime
 *     - variant awtRuntimeElements-macos-arm64
 *          - depends on org.jetbrains.skiko:skiko-awt-runtime-macos-arm64
 *
 *     - variant awtRuntimeElements-macos-x64
 *          - depends on org.jetbrains.skiko:skiko-awt-runtime-macos-x64
 * ...
 * ```
 *
 * This allows Gradle consumers to add a single, universal dependency
 * ```
 * dependencies {
 *      implementation("org.jetbrains.skiko:skiko-awt-runtime:...")
 * }
 * ```
 *
 * which resolves the correct artifact for the current platform.
 */
private fun SkikoPublishingContext.configureAwtRuntimeJarPublication() {
    val allVariantConfigs = awtRuntimeTargets.flatMap { (os, arch) ->
        projectContext.skiko.requestedGpuBackends.map { backend ->
            val variantId = backend.id
            project.configurations.create("awtRuntimeElements-${targetId(os, arch)}-$variantId").apply {
                attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
                attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
                attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements.JAR))
                attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
                attributes.attribute(
                    TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    project.objects.named(TargetJvmEnvironment.STANDARD_JVM)
                )
                attributes.attribute(
                    OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                    project.objects.named(
                        when (os) {
                            OS.Linux -> OperatingSystemFamily.LINUX
                            OS.Windows -> OperatingSystemFamily.WINDOWS
                            OS.MacOS -> OperatingSystemFamily.MACOS
                            else -> error("Unsupported OS for awtRuntimeElements: $os")
                        }
                    )
                )
                attributes.attribute(
                    MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                    project.objects.named(
                        when (arch) {
                            Arch.X64 -> MachineArchitecture.X86_64
                            Arch.Arm64 -> MachineArchitecture.ARM64
                            else -> error("Unsupported arch for awtRuntimeElements: $arch")
                        }
                    )
                )
                attributes.attribute(
                    SkikoGpuBackendAttribute.ATTRIBUTE,
                    project.objects.named(SkikoGpuBackendAttribute::class.java, variantId)
                )
                dependencies.add(
                    project.dependencies.create(
                        SkikoArtifacts.groupId,
                        SkikoArtifacts.jvmRuntimeArtifactIdFor(os, arch),
                        skiko.deployVersion
                    )
                )
            }
        }
    }

    /* Create a new software component and add all variants */
    val component = project.serviceOf<SoftwareComponentFactory>().adhoc("awtRuntimeElements")
    allVariantConfigs.forEach { variant ->
        component.addVariantsFromConfiguration(variant) {
            mapToMavenScope("runtime")
        }
    }

    /* Create the actual publication for this */
    publications {
        create("awtRuntimeElements", MavenPublication::class.java) {
            from(component)
            pomNameForPublication[name] = "Skiko JVM Runtime"
            groupId = SkikoArtifacts.groupId
            artifactId = SkikoArtifacts.jvmRuntimeArtifactId
            version = skiko.deployVersion

            /*
            The entire machinery only works with Gradle attributes;
            therefore, we do not add any dependencies to the maven pom file
             */
            pom {
                withXml {
                    val dependencyNodes = asElement().getElementsByTagName("dependencies")
                    for (i in 0 until dependencyNodes.length) {
                        dependencyNodes.item(i).parentNode.removeChild(dependencyNodes.item(i))
                    }
                }
            }
        }
    }
}

/**
 * Adds dependency constraints from the skiko-awt (Kotlin) publication to all skiko-awt-runtime-* (JNI) artifacts.
 * This ensures compatibility between the Kotlin and native runtime artifacts.
 *
 * Constraints are added to the awt target's configurations, which automatically propagates them to both:
 * - Maven POM (via dependencyManagement section)
 * - Gradle Module Metadata (via dependencyConstraints in variants)
 */
private fun SkikoPublishingContext.configureAwtPublicationConstraints() {
    // Add constraints to Gradle configurations
    // This will automatically generate both POM dependencyManagement and Gradle Module Metadata dependencyConstraints
    listOf("awtApiElements", "awtRuntimeElements").forEach { configName ->
        project.configurations.findByName(configName)?.let { config ->
            // Note: "!!" suffix is used to enforce a strict version
            // See https://docs.gradle.org/current/userguide/dependency_versions.html#sec:rich-version-constraints

            // Add constraint for the uber runtime artifact
            config.dependencyConstraints.add(
                project.dependencies.constraints.create(
                    "${SkikoArtifacts.groupId}:${SkikoArtifacts.jvmRuntimeArtifactId}:${skiko.deployVersion}!!"
                )
            )

            // Add constraints for platform-specific runtime artifacts
            awtRuntimeTargets.forEach { (os, arch) ->
                config.dependencyConstraints.add(
                    project.dependencies.constraints.create(
                        "${SkikoArtifacts.groupId}:${SkikoArtifacts.jvmRuntimeArtifactIdFor(os, arch)}:${skiko.deployVersion}!!"
                    )
                )
            }
        }
    }
}

private fun SkikoPublishingContext.configureAdditionalRuntimeLibrariesPublication() = publications {
    additionalRuntimeLibraries.forEach {
        it.registerMavenPublication(this, emptySourcesJar, pomNameForPublication)
    }
}

private fun SkikoPublishingContext.configureWebPublication() = publications {
    if (!project.supportWeb) return@publications
    create("skikoWasmRuntime", MavenPublication::class.java) {
        pomNameForPublication[name] = "Skiko WASM Runtime"
        artifactId = SkikoArtifacts.jsWasmArtifactId
        artifact(project.tasks.named("skikoWasmJar").get())
        artifact(emptySourcesJar)
    }
}

private fun SkikoPublishingContext.configureAndroidPublication() = publications {
    if (!project.supportAndroid) return@publications
    pomNameForPublication["androidRelease"] = "Skiko Android Runtime"
}

private fun SkikoPublishingContext.configurePomNames() = publications {
    val publicationsWithoutPomNames = this.toList().filter { it.name !in pomNameForPublication }
    if (publicationsWithoutPomNames.isNotEmpty()) {
        error("Publications with unknown POM names: ${publicationsWithoutPomNames.joinToString { "'${it.name}'" }}")
    }
    configureEach {
        this as MavenPublication
        pom.name.set(pomNameForPublication[name]!!)
    }
}

private fun SkikoPublishingContext.configureNativeCinteropPublications() {
    val nativeTargetAttribute = Attribute.of("org.jetbrains.kotlin.native.target", String::class.java)
    kotlin.targets
        .filterIsInstance<KotlinNativeTarget>()
        .forEach { target ->
            val os = target.resolveOs() ?: return@forEach
            val arch = target.resolveArch() ?: return@forEach

            val isUikitSim = target.isUikitSimulator()
            val nativeArtifactId = SkikoArtifacts.nativeArtifactIdFor(os, arch, isUikitSim)
            val cinteropArtifactId = "$nativeArtifactId-cinterop"
            val publicationName = "skikoNativeCinterop${target.name.replaceFirstChar { it.uppercase() }}"

            pomNameForPublication[publicationName] = "Skiko Native Cinterop ${target.name}"
            project.afterEvaluate {
                val component = project.serviceOf<SoftwareComponentFactory>()
                    .adhoc(publicationName)

                skiko.requestedGpuBackends.forEach { requestedGpuBackend ->
                    val cinteropTask = project.tasks
                        .withType(CInteropProcess::class.java)
                        .firstOrNull {
                            it.konanTarget == target.konanTarget &&
                                    it.interopName.endsWith(requestedGpuBackend.id, ignoreCase = true)
                        } ?: return@forEach
                    // Workaround as cinteropTask needs to be unpacked during compilation
                    val zipTaskName = "zip${cinteropTask.name.replaceFirstChar { it.uppercase() }}ForPublication"
                    val zipTask = project.tasks.register<Zip>(zipTaskName) {
                        dependsOn(cinteropTask)
                        from(cinteropTask.klibDirectory)
                        destinationDirectory.set(project.layout.buildDirectory.dir("cinterop-publications"))
                        archiveBaseName.set("${target.name}-cinterop-${requestedGpuBackend.id}")
                        archiveExtension.set("klib")
                    }

                    val configName =
                        "skikoNativeCinteropElements${target.name.replaceFirstChar { it.uppercase() }}${requestedGpuBackend.id.replaceFirstChar { it.uppercase() }}"

                    val variantElements = project.configurations.create(configName) {
                        isCanBeResolved = false
                        isCanBeConsumed = true
                        attributes {
                            // By default consumers look for kotlin-api not kotlin-cinterop
                            attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_API))
                            attribute(
                                Category.CATEGORY_ATTRIBUTE,
                                project.objects.named(Category::class.java, Category.LIBRARY)
                            )
                            attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)
                            attribute(nativeTargetAttribute, target.konanTarget.name)
                            @OptIn(ExperimentalKotlinGradlePluginApi::class)
                            attribute(
                                KlibPackaging.ATTRIBUTE,
                                project.objects.named(KlibPackaging::class.java, KlibPackaging.PACKED)
                            )
                            attribute(
                                SkikoGpuBackendAttribute.ATTRIBUTE,
                                project.objects.named(
                                    SkikoGpuBackendAttribute::class.java,
                                    requestedGpuBackend.id.lowercase()
                                )
                            )
                        }
                        outgoing.artifact(zipTask) {
                            extension = "klib"
                            classifier = requestedGpuBackend.id.lowercase()
                        }
                    }

                    component.addVariantsFromConfiguration(variantElements) {
                        mapToMavenScope("compile")
                    }
                }

                project.extensions.getByType(PublishingExtension::class.java)
                    .publications.apply {
                        create(publicationName, MavenPublication::class.java) {
                            from(component)
                            groupId = SkikoArtifacts.groupId
                            artifactId = cinteropArtifactId
                            version = skiko.deployVersion
                            artifact(emptySourcesJar)
                            pom.withXml {
                                val deps = asElement().getElementsByTagName("dependencies")
                                for (i in 0 until deps.length) deps.item(i).parentNode.removeChild(deps.item(i))
                            }
                        }
                    }

                project.configurations
                    .findByName("${target.name}ApiElements")
                    ?.dependencies
                    ?.add(
                        project.dependencies.create(
                            SkikoArtifacts.groupId,
                            cinteropArtifactId,
                            skiko.deployVersion
                        )
                    )
            }
        }
}

fun KotlinNativeTarget.resolveOs() = when {
    name.startsWith("macos") -> OS.MacOS
    name.contains("Simulator", ignoreCase = true) -> OS.IOS
    name.startsWith("ios") -> OS.IOS
    name.startsWith("tvos") -> OS.TVOS
    name.startsWith("linux") -> OS.Linux
    else -> null
}

fun KotlinNativeTarget.resolveArch() = when {
    name.contains("Arm64", ignoreCase = true) -> Arch.Arm64
    name.contains("X64", ignoreCase = true) -> Arch.X64
    else -> null
}
