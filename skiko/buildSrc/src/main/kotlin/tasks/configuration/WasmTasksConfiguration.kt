package tasks.configuration

import Arch
import CompileSkikoCppTask
import IMPORT_GENERATOR
import LinkSkikoWasmTask
import OS
import SkikoProjectContext
import compilerForTarget
import currentSkikoExtensionModuleOrNull
import linkerForTarget
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.registering
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import projectDirs
import registerOrGetSkiaDirProvider
import skikoCoreModule
import skikoExtensionModules
import supportWeb
import targetId
import tasks.GenerateWasmExportsListTask
import wasmImport
import java.io.ByteArrayOutputStream
import java.io.File

private val Project.setupMjs
    get() = wasmImport("setup.mjs")

private val Project.sideModuleSetupMjs
    get() = wasmImport("$name.mjs")

private val Project.setupReexportMjs
    get() = wasmImport("js-$name-reexport-symbols.mjs")

private val Project.skikoTestMjs
    get() = wasmImport("skiko-test.mjs")

fun SkikoProjectContext.declareWasmTasks(
    isSideModule: Boolean = false,
    extraLibraries: List<String> = emptyList(),
    extraIncludeDirs: List<File> = emptyList()
) {
    if (!project.supportWeb) {
        return
    }

    val skiaWasmDir = registerOrGetSkiaDirProvider(OS.Wasm, Arch.Wasm, false)
    val skiaBinDir = skiaWasmDir.map { it.resolve("out/${buildType.id}-${targetId(OS.Wasm, Arch.Wasm)}") }
    val compileWasm by project.tasks.registering(CompileSkikoCppTask::class) {
        dependsOn(skiaWasmDir)
        val binaryModule = project.currentSkikoExtensionModuleOrNull() ?: skikoCoreModule
        val moduleBinaryInputs = binaryModule.resolveBinaryInputs(
            OS.Wasm,
            Arch.Wasm,
            TargetEnv.WASM,
            skiaBinDir.get().absolutePath
        )

        compiler.set(compilerForTarget(OS.Wasm, Arch.Wasm))
        buildTargetOS.set(OS.Wasm)
        buildTargetArch.set(Arch.Wasm)
        buildVariant.set(buildType)

        val srcDirs = projectDirs("src/commonMain/cpp/common", "src/webMain/cpp", "src/nativeJsMain/cpp") +
                if (skiko.includeTestHelpers) projectDirs("src/nativeJsTest/cpp") else emptyList()
        sourceRoots.set(srcDirs)

        includeHeadersNonRecursive(project.projectDir.resolve("src/nativeJsMain/cpp"))
        includeHeadersNonRecursive(project.projectDir.resolve("src/webMain/cpp"))
        includeHeadersNonRecursive(project.projectDir.resolve("src/commonMain/cpp/common/include"))

        extraIncludeDirs.forEach {
            includeHeadersNonRecursive(it)
        }
        moduleBinaryInputs.includeDirs.forEach {
            includeHeadersNonRecursive(it)
        }

        includeHeadersNonRecursive(skiaHeadersDirs(skiaWasmDir.get()))

        flags.set(
            buildList {
                addAll(skiaPreprocessorFlags(OS.Wasm, buildType))
                addAll(buildType.clangFlags)
                add("-fno-rtti")
                add("-fno-exceptions")
                add("-fPIC")
                add("-fvisibility=hidden")
                add("-fvisibility-inlines-hidden")
                if (skiko.isWasmBuildWithProfiling) add("--profiling")
                addAll(moduleBinaryInputs.compileFlags)
            }
        )
    }
    // TODO: experimental creation of uber skia static archive
    // throws duplicate object files which prevents duplicate errors/warning
    val mainModuleSkiaLibraries = skiaBinDir.map { dir ->
        skikoCoreModule
            .resolveBinaryInputs(OS.Wasm, Arch.Wasm, TargetEnv.WASM, dir.absolutePath)
            .staticArchivePaths
            .map(::File)
    }
    val mergedSkiaWasmArchiveFile = project.layout.buildDirectory.file(
        "out/skia-wasm-main-module/${buildType.id}-${targetId(OS.Wasm, Arch.Wasm)}/libskia-main-module.wasm.a"
    )
    val mergeSkiaWasmMainModuleArchive = if (isSideModule) null else project.tasks.register("mergeSkiaWasmMainModuleArchive") {
        dependsOn(skiaWasmDir)
        inputs.files(mainModuleSkiaLibraries)
        outputs.file(mergedSkiaWasmArchiveFile)

        doLast {
            val archiveFile = mergedSkiaWasmArchiveFile.get().asFile
            val workDir = archiveFile.parentFile.resolve("objects")
            val selectedMembers = mutableListOf<File>()
            val seenMemberNames = mutableSetOf<String>()

            project.delete(workDir)
            workDir.mkdirs()

            mainModuleSkiaLibraries.get().forEachIndexed { archiveIndex, inputArchive ->
                val extractDir = workDir.resolve("extract-$archiveIndex")
                val memberNamesOutput = ByteArrayOutputStream()

                extractDir.mkdirs()

                project.exec {
                    executable = "emar"
                    args("t", inputArchive.absolutePath)
                    standardOutput = memberNamesOutput
                }

                project.exec {
                    executable = "emar"
                    workingDir = extractDir
                    args("x", inputArchive.absolutePath)
                }

                memberNamesOutput.toString()
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { memberName ->
                        if (seenMemberNames.add(memberName)) {
                            val extractedMember = extractDir.resolve(memberName)
                            val selectedMember = workDir.resolve(memberName)
                            extractedMember.copyTo(selectedMember)
                            selectedMembers.add(selectedMember)
                        }
                    }
            }

            archiveFile.parentFile.mkdirs()
            project.delete(archiveFile)
            project.exec {
                executable = "emar"
                workingDir = workDir
                args(listOf("crs", archiveFile.absolutePath) + selectedMembers.map { it.name })
            }
        }
    }

    val generateWasmExportsList: TaskProvider<GenerateWasmExportsListTask>? =
        if (isSideModule) null else project.tasks.register<GenerateWasmExportsListTask>("generateWasmExportsList") {
            dependsOn(compileWasm)
            outputDir.set(
                project.layout.buildDirectory.dir(
                    "wasm-exports/${buildType.id}-${targetId(OS.Wasm, Arch.Wasm)}"
                )
            )
            coreObjectFiles.from(
                compileWasm.map { it.outDir.get().asFile.walk()
                    .filter { f -> f.name.endsWith(".o") }
                    .toList() }
            )
            coreSkiaArchives.from(mainModuleSkiaLibraries)
        }

    fun LinkSkikoWasmTask.configureCommon(prefixPath: String) {
        dependsOn(compileWasm)
        dependsOn(skiaWasmDir)
        if (mergeSkiaWasmMainModuleArchive != null) {
            dependsOn(mergeSkiaWasmMainModuleArchive)
        }
        if (generateWasmExportsList != null) {
            dependsOn(generateWasmExportsList)
        }

        linker.set(linkerForTarget(OS.Wasm, Arch.Wasm))
        buildTargetOS.set(OS.Wasm)
        buildTargetArch.set(Arch.Wasm)
        buildVariant.set(buildType)

        val moduleInputs = (project.currentSkikoExtensionModuleOrNull() ?: skikoCoreModule).resolveBinaryInputs(
            OS.Wasm,
            Arch.Wasm,
            TargetEnv.WASM,
            skiaBinDir.get().absolutePath
        )

        libFiles = if (isSideModule) {
            val moduleLibs = moduleInputs.staticArchivePaths
            project.files(moduleLibs + extraLibraries)
        } else {
            project.files(mergedSkiaWasmArchiveFile, *extraLibraries.toTypedArray())
        }

        objectFiles = project.fileTree(compileWasm.map { it.outDir.get() }) {
            include("**/*.o")
        }

        val coreProject = if (project.name == "skiko") project else project.parent!!
        val skikoCallbacksJs = coreProject.layout.projectDirectory.file("src/webMain/resources/skikoCallbacks.js")

        externPostJs.from(
            skikoCallbacksJs,
            project.layout.projectDirectory.file(prefixPath)
        )

        flags.addAll(buildList {
            if (isSideModule) {
                addAll(listOf(
                    "-s", "SIDE_MODULE=1",
                ))
            } else {
                addAll(moduleInputs.linkFlags)
            }

            addAll(
                listOf(
                    "-s", "OFFSCREEN_FRAMEBUFFER=1",
                    "-s", "ALLOW_MEMORY_GROWTH=1", // TODO: Is there a better way? Should we use `-s INITIAL_MEMORY=X`?
                    "-s", "EXPORT_ES6=1",
                    "-s", "SUPPORT_LONGJMP=wasm",
                    // -O2 saves 800kB for the output file, and ~100kB for transferred size.
                    // -O3 breaks the exports in js/mjs files. skiko.wasm size is the same though
                    "-O2"
                )
            )

            if (skiko.isWasmBuildWithProfiling) add("--profiling")
        })

        if (generateWasmExportsList != null) {
            val exportedFunctionsFile = generateWasmExportsList
                .flatMap { it.outputDir.file("exported_functions.txt") }
            flags.add("-s")
            flags.add(exportedFunctionsFile.map { "EXPORTED_FUNCTIONS=@${it.asFile.absolutePath}" })
        }

        doLast {
            // skiko.mjs is referenced in karma.config.d/*/config.js
            // so symbols must be replaced right after linking
            val jsFile = outDir.asFile.get().walk().first { it.name == jsOutputFileName.get() }
            if (jsFile.extension != "mjs") {
                return@doLast
            }

            val isEnvironmentNodeCheckRegex = Regex(
                // spacing is different in release and debug builds
                """if\s*\(ENVIRONMENT_IS_NODE\)\s*\{"""
            )

            val originalContent = jsFile.readText()
            val newContent = originalContent
                .replace(isEnvironmentNodeCheckRegex, "if (false) {") // to make webpack erase this part
            jsFile.writeText(newContent)
        }
    }

    val linkWasm by project.tasks.registering(LinkSkikoWasmTask::class) {
        dependsOn(
            kotlin.wasmJs().compilations["main"].compileTaskProvider,
            kotlin.js().compilations["main"].compileTaskProvider
        )

        buildSuffix.set("es6")
        jsOutputFileName.set(if (isSideModule) "${project.name}.wasm" else "skiko.mjs")  // this determines the name .wasm file too
        libOutputFileName.set(if (isSideModule) "${project.name}.wasm" else "skiko.wasm")

        configureCommon(if (isSideModule) project.sideModuleSetupMjs.normalize().absolutePath else project.setupMjs.normalize().absolutePath)
    }

    val linkWasmD8WithES6 by project.tasks.registering(LinkSkikoWasmTask::class) {
        dependsOn(
            kotlin.wasmJs().compilations["main"].compileTaskProvider,
            kotlin.js().compilations["main"].compileTaskProvider
        )

        buildSuffix.set("d8")
        jsOutputFileName.set(if (isSideModule) "${project.name}d8.wasm" else "skikod8.mjs")
        libOutputFileName.set(if (isSideModule) "${project.name}d8.wasm" else "skikod8.wasm")

        flags.addAll(listOf("-s", "ENVIRONMENT=shell"))

        configureCommon(if (isSideModule) project.sideModuleSetupMjs.normalize().absolutePath else project.setupMjs.normalize().absolutePath)
    }

    // skikoWasmJar is used by task name
    val skikoWasmJar by project.tasks.registering(Jar::class) {
        // We produce jar that contains .js of wrapper/bindings and .wasm with Skia + bindings.
        from(project.setupReexportMjs.parentFile) {
            include(project.setupReexportMjs.name)
            if (isSideModule) {
                include(project.sideModuleSetupMjs.name)
            }
        }

        from(linkWasm) {
            include("*.wasm")
            include("*.mjs")
        }

        from(linkWasmD8WithES6) {
            if (!isSideModule) {
                include("*.mjs")
                filesMatching("*.mjs") {
                    filter { it.replace("skikod8.wasm", "skiko.wasm") }
                }
            }
        }

        archiveBaseName.set("skiko-wasm")
        doLast {
            println("Wasm and JS at: ${archiveFile.get().asFile.absolutePath}")
        }
    }
}

fun SkikoProjectContext.configureGenerateWasmExportsList() {
    val task = project.tasks.named<GenerateWasmExportsListTask>("generateWasmExportsList")

    project.skikoExtensionModules().forEach { module ->
        val moduleProject = project.findProject(module.projectPath) ?: return@forEach
        // Force the side-module subproject to be configured before we look up its
        // `compileWasm` task. Otherwise, the task may not yet be registered.
        project.evaluationDependsOn(module.projectPath)
        val moduleCompile = moduleProject.tasks.named<CompileSkikoCppTask>("compileWasm")

        task.configure {
            dependsOn(moduleCompile)
            moduleObjectFiles.from(
                moduleCompile.map { it.outDir.get().asFile.walk()
                    .filter { f -> f.name.endsWith(".o") }
                    .toList() }
            )

            val skiaWasmDir = registerOrGetSkiaDirProvider(OS.Wasm, Arch.Wasm, false)
            val skiaBinDir = skiaWasmDir.map { it.resolve("out/${buildType.id}-${targetId(OS.Wasm, Arch.Wasm)}") }
            module.resolveBinaryInputs(OS.Wasm, Arch.Wasm, TargetEnv.WASM, skiaBinDir.get().absolutePath).staticLibBaseNames.forEach { baseName ->
                moduleSkiaArchives.from(skiaBinDir.map { it.resolve("lib$baseName.wasm.a") })
            }
        }
    }
}

abstract class AbstractImportGeneratorCompilerPluginSupportPlugin(
    val compilationName: String,
    private val moduleKind: String,
    private val targetModuleProvider: (Project) -> String,
    private val outputFileProvider: (Project) -> File,
    private val prefixFileProvider: (Project) -> File,
    private val reexportFileProvider: ((Project) -> File)?
) : KotlinCompilerPluginSupportPlugin {
    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        val outputFile = outputFileProvider(project)
        val prefixFile = prefixFileProvider(project)
        val reexportFile = reexportFileProvider?.invoke(project)

        return project.provider {
            buildList {
                add(SubpluginOption("import-generator-path", outputFile.normalize().absolutePath))
                add(SubpluginOption("import-generator-prefix", prefixFile.normalize().absolutePath))
                add(SubpluginOption("import-generator-kind", moduleKind))
                add(SubpluginOption("import-generator-target-module", targetModuleProvider(project)))
                if (reexportFile != null) {
                    add(SubpluginOption("import-generator-reexport-path", reexportFile.normalize().absolutePath))
                }
            }
        }
    }

    override fun getCompilerPluginId() = "org.jetbrains.skiko.imports.generator"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(SkikoArtifacts.groupId, IMPORT_GENERATOR)

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        return ((kotlinCompilation.platformType == KotlinPlatformType.wasm) || (kotlinCompilation.platformType == KotlinPlatformType.js))
                && kotlinCompilation.name == compilationName
    }
}

class MainWasmImportsGeneratorPlugin : AbstractImportGeneratorCompilerPluginSupportPlugin(
    KotlinCompilation.MAIN_COMPILATION_NAME,
    "main",
    { "./skiko.mjs" },
    { it.setupMjs },
    { it.projectDir.resolve("src/webMain/resources/pre-setup.mjs") },
    { it.setupReexportMjs }
)

class SideWasmImportsGeneratorPlugin : AbstractImportGeneratorCompilerPluginSupportPlugin(
    KotlinCompilation.MAIN_COMPILATION_NAME,
    "side",
    { "./${it.name}.mjs" },
    { it.sideModuleSetupMjs },
    { it.projectDir.resolve("src/webMain/resources/pre-${it.name}.mjs") },
    { it.setupReexportMjs }
)

class WasmTestImportsGeneratorPlugin : AbstractImportGeneratorCompilerPluginSupportPlugin(
    KotlinCompilation.TEST_COMPILATION_NAME,
    "main",
    { "./skiko-test.mjs" },
    { it.skikoTestMjs },
    { it.projectDir.resolve("src/webMain/resources/pre-skiko-test.mjs") },
    null
)

fun KotlinJsTargetDsl.setupImportsGeneratorPlugin(isSideModule: Boolean = false) {
    val main by compilations.getting
    val test by compilations.getting

    main.compileTaskProvider.configure {
        outputs.file(if (isSideModule) project.sideModuleSetupMjs else project.setupMjs)
    }

    test.compileTaskProvider.configure {
        outputs.file(project.skikoTestMjs)
    }

    listOf(main, test).forEach {
        // By default, it will try to use the same version as kotlin, because we use version=null in getPluginArtifact.
        // But we don't publish the artifact, therefore we substitute it for project dependency.
        it.configurations.pluginConfiguration.resolutionStrategy.dependencySubstitution {
            substitute(module("${SkikoArtifacts.groupId}:$IMPORT_GENERATOR"))
                .using(project(":import-generator"))
        }
    }
}