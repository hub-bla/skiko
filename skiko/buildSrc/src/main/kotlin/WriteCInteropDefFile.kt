import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class WriteCInteropDefFile : DefaultTask() {
    @get:Input
    abstract val linkerOpts: ListProperty<String>
    @get:Input
    abstract val staticLibraries: ListProperty<String>
    @get:Input
    abstract val libraryPaths: ListProperty<String>
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val outputFile = outputFile.get().asFile
        outputFile.parentFile.mkdirs()

        outputFile.bufferedWriter().use { writer ->
            val linkerOpts = linkerOpts.get()
            if (linkerOpts.isNotEmpty()) {
                writer.appendLine("linkerOpts=${linkerOpts.joinToString(" ")}")
            }

            val staticLibraries = staticLibraries.get()
            if (staticLibraries.isNotEmpty()) {
                writer.appendLine("staticLibraries=${staticLibraries.joinToString(" ")}")
            }

            val libraryPaths = libraryPaths.get()
            if (libraryPaths.isNotEmpty()) {
                writer.appendLine("libraryPaths=${libraryPaths.joinToString(" ")}")
            }
        }
    }
}
