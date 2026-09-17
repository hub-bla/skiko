@file:OptIn(org.jetbrains.skiko.ExperimentalSkikoApi::class)

package org.jetbrains.skia.gpu

import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpuSurfaceTest {
    @Test
    fun closeIsIdempotent() {
        val surface = Surface.makeRasterN32Premul(1, 1)
        var disposeCount = 0
        val gpuSurface = GpuSurface(surface) {
            disposeCount++
            surface.close()
        }

        gpuSurface.close()
        gpuSurface.close()

        assertTrue(gpuSurface.isClosed)
        assertEquals(1, disposeCount)
    }
}