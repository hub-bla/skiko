package org.jetbrains.skiko

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class ImportGeneratorRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true
    override val pluginId: String = "skiko-import-generator"

    override fun ExtensionStorage.registerExtensions(
        configuration: CompilerConfiguration,
    ) {
        val path = configuration.get(ImportGeneratorConfigurationKeys.PATH)!!
        val prefix = configuration.get(ImportGeneratorConfigurationKeys.PREFIX)
        val reexportPath = configuration.get(ImportGeneratorConfigurationKeys.REEXPORT_PATH)
        val targetModule = configuration.get(ImportGeneratorConfigurationKeys.TARGET_MODULE)!!

        val extraReexportHeader = when (configuration.get(ImportGeneratorConfigurationKeys.KIND)!!) {
            ModuleKind.Main -> ImportGeneratorExtension.MAIN_MODULE_REEXPORT_HEADER
            ModuleKind.Side -> ImportGeneratorExtension.SIDE_MODULE_REEXPORT_HEADER
        }

        IrGenerationExtension.registerExtension(
            ImportGeneratorExtension(path, prefix, reexportPath, targetModule, extraReexportHeader)
        )
    }
}
