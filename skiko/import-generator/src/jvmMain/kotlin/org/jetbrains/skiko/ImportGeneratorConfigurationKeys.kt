package org.jetbrains.skiko

import org.jetbrains.kotlin.config.CompilerConfigurationKey

object ImportGeneratorConfigurationKeys {

    val PATH: CompilerConfigurationKey<String> = CompilerConfigurationKey.create(
        PATH_OPTION_NAME
    )

    val PREFIX: CompilerConfigurationKey<String> = CompilerConfigurationKey.create(
        PREFIX_OPTION_NAME
    )

    val REEXPORT_PATH: CompilerConfigurationKey<String> = CompilerConfigurationKey.create(
        REEXPORT_OPTION_NAME
    )

    val KIND: CompilerConfigurationKey<ModuleKind> = CompilerConfigurationKey.create(
        KIND_OPTION_NAME
    )

    val TARGET_MODULE: CompilerConfigurationKey<String> = CompilerConfigurationKey.create(
        TARGET_MODULE_OPTION_NAME
    )
}
