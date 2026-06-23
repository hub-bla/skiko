package org.jetbrains.skiko.sample.extensions

import kotlinx.coroutines.runBlocking

internal fun loadSkottieAnimationPlayer(): SkottieAnimationPlayer =
    makeSkottieAnimationPlayer(runBlocking { loadResourceAsText(ORBIT_ANIMATION_RESOURCE) })
