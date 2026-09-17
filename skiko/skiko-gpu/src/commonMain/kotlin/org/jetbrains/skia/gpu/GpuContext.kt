@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    org.jetbrains.skiko.ExperimentalSkikoApi::class,
)

package org.jetbrains.skia.gpu

import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.InternalSkikoApi

@InternalSkikoApi
interface GpuContextBackend {
    fun makeSurface(
        width: Int,
        height: Int,
        texturePtr: NativePointer,
        colorSpace: ColorSpace?,
        surfaceProps: SurfaceProps?,
    ): GpuSurface?

    fun submit(surface: GpuSurface, syncCpu: Boolean)
    fun close()
}

/** A Skia GPU context supplied by the selected GPU backend. */
@ExperimentalSkikoApi
class GpuContext @InternalSkikoApi constructor(
    private val backend: GpuContextBackend,
) {
    private var closed = false

    fun makeSurface(
        width: Int,
        height: Int,
        texturePtr: NativePointer,
        colorSpace: ColorSpace? = ColorSpace.sRGB,
        surfaceProps: SurfaceProps? = SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN),
    ): GpuSurface? {
        check(!closed) { "GPU context is closed" }
        return backend.makeSurface(width, height, texturePtr, colorSpace, surfaceProps)
    }

    fun submit(surface: GpuSurface, syncCpu: Boolean = false) {
        check(!closed) { "GPU context is closed" }
        check(!surface.isClosed) { "GPU surface is closed" }
        backend.submit(surface, syncCpu)
    }

    fun close() {
        if (!closed) {
            closed = true
            backend.close()
        }
    }
}