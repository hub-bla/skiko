package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.DirectContext
import kotlin.time.TimeSource

private val initialTime = TimeSource.Monotonic.markNow()

internal interface Redrawer {
    fun dispose()
    fun needRender(throttledToVsync: Boolean)
    fun renderImmediately()
    fun syncBounds() = Unit
    fun update(nanoTime: Long = initialTime.elapsedNow().inWholeNanoseconds)
    fun setVisible(isVisible: Boolean) = Unit
    val renderInfo: String

    val directContext: DirectContext?
        get() = null
}