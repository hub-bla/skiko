package org.jetbrains.skiko.sample.extensions

import org.jetbrains.skiko.loadBytesFromPath

expect fun resourcePath(resourceId: String): String

suspend inline fun loadResourceAsBytes(resourcePath: String): ByteArray =
    loadBytesFromPath(resourcePath(resourcePath))

suspend inline fun loadResourceAsText(resourcePath: String): String =
    loadResourceAsBytes(resourcePath).decodeToString()
