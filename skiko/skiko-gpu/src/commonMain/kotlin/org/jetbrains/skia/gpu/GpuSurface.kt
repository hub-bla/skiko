package org.jetbrains.skia.gpu

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.InternalSkikoApi

/** A Skia surface backed by a caller-owned GPU resource. */
@ExperimentalSkikoApi
class GpuSurface @InternalSkikoApi constructor(
    @InternalSkikoApi val surface: Surface,
    private val dispose: () -> Unit,
) {
    private var closed = false

    val canvas: Canvas get() = surface.canvas
    val isClosed: Boolean get() = closed

    fun close() {
        if (!closed) {
            closed = true
            dispose()
        }
    }
}