package org.jetbrains.skiko.sample.extensions

internal suspend fun loadSkottieAnimationPlayer(): SkottieAnimationPlayer =
    makeSkottieAnimationPlayer(loadResourceAsText(ORBIT_ANIMATION_RESOURCE))
