package tasks

import OS
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import tasks.configuration.generateDefFile
import tasks.configuration.generateVersionScript
import java.io.ByteArrayOutputStream
import java.io.File

abstract class GenerateSymbolsListTask : DefaultTask() {
    @get:Input
    abstract val targetOs: Property<OS>

    @get:InputFiles
    abstract val coreObjectFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val moduleObjectFiles: ConfigurableFileCollection

    @get:InputFiles
    abstract val skiaLibs: ConfigurableFileCollection

    @get:InputFiles
    abstract val moduleLibs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val os = targetOs.get()
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val coreExports       = outDir.resolve("core_exports.txt")
        val extImports        = outDir.resolve("ext_imports.txt")
        val symbolsFiltered   = outDir.resolve("symbols_filtered.txt")
        val symbolsUnexported = outDir.resolve("symbols_unexported.txt")

        // 1. core exports
        val coreExportedList = extractSymbols(skiaLibs.files.toList() + coreObjectFiles.files.toList(), true)
        coreExports.writeText(coreExportedList.sorted().joinToString("\n"))

        // 2. all ext imports
        val extImportedList = extractSymbols(moduleObjectFiles.files.toList() + moduleLibs.files.toList(), false).toMutableList()

        // also keep jvm infrastructure globals
        extImportedList.addAll(coreExportedList.filter {
            it.contains("_jvm") || it.contains("_JNI") || it.contains("_Java_")
        })

        extImports.writeText(extImportedList.distinct().sorted().joinToString("\n"))

        // 4. initial keep list = intersection of ext imports + JNI with core exports
        val coreExportsSet = coreExportedList.toSet()
        val keepSet = extImportedList.filter { it in coreExportsSet }.toSet()
        symbolsFiltered.writeText(keepSet.sorted().joinToString("\n"))

        // 5. unexported = core exports minus what strip decided to keep
        val unexportedSet = coreExportsSet - keepSet
        symbolsUnexported.writeText(unexportedSet.sorted().joinToString("\n"))

        if (os.isLinux) {
            val versionScript = outDir.resolve("symbols.map")
            generateVersionScript(symbolsUnexported.toPath(), versionScript.toPath())
        }

        if (os.isWindows) {
            val defFile = outDir.resolve("symbols.def")
            generateDefFile(symbolsFiltered.toPath(), defFile.toPath())
        }

        logger.lifecycle("Symbols to keep: ${keepSet.size}, to hide: ${unexportedSet.size}")
    }

    private fun extractSymbols(files: List<File>, exported: Boolean): List<String> {
        val os = targetOs.get()
        val result = mutableSetOf<String>()
        files.forEach { file ->
            val out = ByteArrayOutputStream()
            when {
                os.isMacOs -> {
                    val nmFlags = if (exported) listOf("-g", "-U") else listOf("-u")
                    project.exec {
                        commandLine("nm", *nmFlags.toTypedArray(), file.absolutePath)
                        standardOutput = out
                    }
                    out.toString().lines().forEach { line ->
                        val s = line.trim().split(" ").last()
                        if (s.isNotEmpty() && !s.contains(":") && !s.startsWith("/")) {
                            result.add(s)
                        }
                    }
                }
                os.isLinux -> {
                    val nmFlags = if (exported) listOf("-g", "--defined-only") else listOf("-u")
                    project.exec {
                        commandLine("nm", *nmFlags.toTypedArray(), file.absolutePath)
                        standardOutput = out
                    }
                    out.toString().lines().forEach { line ->
                        val s = line.trim().split(" ").last()
                        if (s.isNotEmpty() && !s.contains(":") && !s.startsWith("/")) {
                            result.add(s)
                        }
                    }
                }
                else -> { // Windows
                    project.exec {
                        commandLine("dumpbin", "/SYMBOLS", file.absolutePath)
                        standardOutput = out
                    }
                    out.toString().lines().forEach { line ->
                        if (line.contains("External")) {
                            val isUndef = line.contains("UNDEF")
                            if (exported && !isUndef) {
                                val s = line.substringAfter("|").trim().substringBefore(" ")
                                if (s.isNotEmpty() && !s.startsWith("__imp_") && !s.startsWith(".refptr") && !s.startsWith("__real@") && !s.startsWith("__xmm@")) {
                                    result.add(s)
                                }
                            } else if (!exported && isUndef) {
                                val s = line.substringAfter("|").trim().substringBefore(" ")
                                if (s.isNotEmpty() && !s.startsWith("__imp_") && !s.startsWith(".refptr") && !s.startsWith("__real@") && !s.startsWith("__xmm@")) {
                                    result.add(s)
                                }
                            }
                        }
                    }
                }
            }
        }
        return result.toList()
    }
}
