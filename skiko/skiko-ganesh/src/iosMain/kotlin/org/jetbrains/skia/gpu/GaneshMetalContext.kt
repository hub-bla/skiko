@file:OptIn(kotlin.ExperimentalStdlibApi::class, org.jetbrains.skiko.ExperimentalSkikoApi::class, org.jetbrains.skiko.InternalSkikoApi::class)

package org.jetbrains.skia.gpu

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.gpu.ganesh.flushAndSubmit
import org.jetbrains.skia.gpu.ganesh.makeFromBackendRenderTarget
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.InternalSkikoApi
import org.jetbrains.skiko.Logger

private class GaneshMetalContext(private val context: DirectContext) : GpuContextBackend {
    override fun makeSurface(width: Int, height: Int, texturePtr: NativePointer, colorSpace: ColorSpace?, surfaceProps: SurfaceProps?): GpuSurface? {
        val renderTarget = BackendRenderTarget.makeMetal(width, height, texturePtr)
        val surface = Surface.makeFromBackendRenderTarget(context, renderTarget, SurfaceOrigin.TOP_LEFT, SurfaceColorFormat.BGRA_8888, colorSpace, surfaceProps)
        if (surface == null) {
            renderTarget.close()
            return null
        }
        return GpuSurface(surface) {
            surface.close()
            renderTarget.close()
        }
    }

    override fun submit(surface: GpuSurface, syncCpu: Boolean) = surface.surface.flushAndSubmit(syncCpu)
    override fun close() = context.close()
}

fun makeMetalContext(devicePtr: NativePointer, queuePtr: NativePointer): GpuContext =
    GpuContext(makeGaneshMetalContextBackend(devicePtr, queuePtr))

@InternalSkikoApi
fun makeGaneshMetalContextBackend(devicePtr: NativePointer, queuePtr: NativePointer): GpuContextBackend {
    Logger.info { "Metal backend: Ganesh" }
    return GaneshMetalContext(DirectContext.makeMetal(devicePtr, queuePtr))
}