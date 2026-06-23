package org.jetbrains.skiko.sample.extensions

import kotlinx.coroutines.runBlocking

internal fun loadResourceAsBytesBlocking(path: String): ByteArray {
    return runBlocking { loadResourceAsBytes(path) }
}

internal fun loadResourceAsTextBlocking(path: String): String =
    loadResourceAsBytesBlocking(path).decodeToString()

internal fun loadSkottieAnimationPlayerBlocking(): SkottieAnimationPlayer =
    makeSkottieAnimationPlayer(loadResourceAsTextBlocking(ORBIT_ANIMATION_RESOURCE))
