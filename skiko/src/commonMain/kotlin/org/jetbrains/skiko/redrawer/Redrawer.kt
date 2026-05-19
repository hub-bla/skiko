package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.OS
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.InternalSkikoApi
import kotlin.time.TimeSource

private val initialTime = TimeSource.Monotonic.markNow()

@InternalSkikoApi
interface Redrawer {
    fun dispose()
    fun needRender(throttledToVsync: Boolean)
    fun renderImmediately()
    fun syncBounds() = Unit
    fun update(nanoTime: Long = initialTime.elapsedNow().inWholeNanoseconds)
    fun setVisible(isVisible: Boolean) = Unit
    val renderInfo: String
    fun isTransparentBackgroundSupported(): Boolean
}

@InternalSkikoApi
fun defaultIsTransparentBackgroundSupported(layer: SkiaLayer): Boolean {
    if (hostOs == OS.MacOS) {
        // macOS transparency is always supported
        return true
    }

    // for non-macOS in fullscreen transparency is not supported
    return !layer.fullscreen
}
