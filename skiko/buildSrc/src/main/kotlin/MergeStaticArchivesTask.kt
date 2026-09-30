import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject

/** Merges static archives, retaining one copy of each object file. */
abstract class MergeStaticArchivesTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:InputFiles abstract val archives: ListProperty<File>
    @get:Input abstract val appleTarget: Property<Boolean>
    @get:Input abstract val archiverCommand: ListProperty<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction fun merge() {
        val inputs = archives.get()
            .filter(File::isFile)
            .also { require(it.isNotEmpty()) { "No static archives to merge" } }
        val dir = temporaryDir.apply {
            deleteRecursively()
            mkdirs()
        }
        val outputFile = output.get().asFile.apply {
            parentFile.mkdirs()
            delete()
        }

        if (appleTarget.get()) {
            mergeAppleArchives(inputs, outputFile, dir)
        } else {
            createArchive(inputs, outputFile, dir)
        }
    }

    private fun mergeAppleArchives(inputs: List<File>, output: File, dir: File) {
        val lipo = listOf("xcrun", "lipo")
        val architectures = capture(lipo + listOf("-archs", inputs.first().absolutePath))
            .trim()
            .split(' ')
        val slices = architectures.map { architecture ->
            val sliceInputs = inputs.mapIndexed { index, input ->
                if (architectures.size == 1) input else File(dir, "$index-$architecture.a").also { slice ->
                    run(lipo + listOf(
                        input.absolutePath,
                        "-thin", architecture,
                        "-output", slice.absolutePath,
                    ))
                }
            }
            File(dir, "merged-$architecture.a").also { merged ->
                createArchive(sliceInputs, merged, File(dir, architecture))
            }
        }

        val createFatArchiveCommand = lipo +
                listOf("-create") +
                slices.map(File::getAbsolutePath) +
                listOf("-output", output.absolutePath)
        run(createFatArchiveCommand)
    }

    private fun createArchive(inputs: List<File>, output: File, dir: File) {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val seen = hashSetOf<String>()
        val members = mutableListOf<File>()
        inputs.forEachIndexed { index, input ->
            val objects = File(dir, index.toString()).apply { mkdirs() }
            run(archiverCommand.get() + listOf("-x", input.absolutePath), cwd = objects)
            objects.listFiles()
                .orEmpty()
                .filter { it.isFile && !it.name.startsWith("__.SYMDEF") }
                .forEach { objectFile ->
                    val digest = Base64.getEncoder().encodeToString(sha256.digest(objectFile.readBytes()))
                    if (seen.add(digest)) members += objectFile
                }
        }

        output.delete()
        run(archiverCommand.get() + listOf("-crs", output.absolutePath) + members.map(File::getAbsolutePath))
    }

    private fun run(args: List<String>, cwd: File? = null) = execOperations.exec {
        commandLine(*args.toTypedArray())
        workingDir = cwd
    }

    private fun capture(args: List<String>): String {
        val out = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(*args.toTypedArray())
            standardOutput = out
        }
        return out.toString()
    }
}
