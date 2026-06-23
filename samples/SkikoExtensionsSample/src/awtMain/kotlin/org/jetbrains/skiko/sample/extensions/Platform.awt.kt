package org.jetbrains.skiko.sample.extensions

import java.nio.file.Paths

private const val RESOURCES_PATH = "src/commonMain/resources"

actual fun resourcePath(resourceId: String): String =
    Paths.get("${RESOURCES_PATH}/$resourceId").normalize().toAbsolutePath().toString()
