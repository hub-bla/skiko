package org.jetbrains.skiko

const val PATH_OPTION_NAME = "import-generator-path"
const val PREFIX_OPTION_NAME = "import-generator-prefix"
const val REEXPORT_OPTION_NAME = "import-generator-reexport-path"
const val KIND_OPTION_NAME = "import-generator-kind"
const val TARGET_MODULE_OPTION_NAME = "import-generator-target-module"

enum class ModuleKind(val optionValue: String) {
    Main("main"),
    Side("side");

    companion object {
        fun fromOptionValue(value: String): ModuleKind =
            entries.firstOrNull { it.optionValue == value }
                ?: error("Unknown import-generator-kind: $value")
    }
}
