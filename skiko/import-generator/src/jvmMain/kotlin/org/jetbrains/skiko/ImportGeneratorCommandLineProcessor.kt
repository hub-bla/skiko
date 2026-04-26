package org.jetbrains.skiko

import org.jetbrains.kotlin.compiler.plugin.*
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.skiko.ImportGeneratorConfigurationKeys.PATH
import org.jetbrains.skiko.ImportGeneratorConfigurationKeys.PREFIX
import org.jetbrains.skiko.ImportGeneratorConfigurationKeys.REEXPORT_PATH
import org.jetbrains.skiko.ImportGeneratorConfigurationKeys.KIND
import org.jetbrains.skiko.ImportGeneratorConfigurationKeys.TARGET_MODULE

@OptIn(ExperimentalCompilerApi::class)
class ImportGeneratorCommandLineProcessor : CommandLineProcessor {
    override val pluginId = "org.jetbrains.skiko.imports.generator"
    override val pluginOptions = listOf(PATH_OPTION, PREFIX_OPTION, REEXPORT_OPTION, KIND_OPTION, TARGET_MODULE_OPTION)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) =
        when (option) {
            PATH_OPTION -> configuration.put(PATH, value)
            PREFIX_OPTION -> configuration.put(PREFIX, value)
            REEXPORT_OPTION -> configuration.put(REEXPORT_PATH, value)
            KIND_OPTION -> configuration.put(KIND, ModuleKind.fromOptionValue(value))
            TARGET_MODULE_OPTION -> configuration.put(TARGET_MODULE, value)
            else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }

    companion object {
        val PATH_OPTION = CliOption(
            PATH_OPTION_NAME, "<path>",
            "path",
            required = true, allowMultipleOccurrences = false
        )

        val PREFIX_OPTION = CliOption(
            PREFIX_OPTION_NAME, "<path>",
            "prefix",
            required = false, allowMultipleOccurrences = false
        )

        val REEXPORT_OPTION = CliOption(
            REEXPORT_OPTION_NAME, "<path>",
            "reexport",
            required = false, allowMultipleOccurrences = false
        )

        val KIND_OPTION = CliOption(
            KIND_OPTION_NAME, "<main|side>",
            "module kind",
            required = true, allowMultipleOccurrences = false
        )

        val TARGET_MODULE_OPTION = CliOption(
            TARGET_MODULE_OPTION_NAME, "<module>",
            "target module",
            required = true, allowMultipleOccurrences = false
        )
    }
}
