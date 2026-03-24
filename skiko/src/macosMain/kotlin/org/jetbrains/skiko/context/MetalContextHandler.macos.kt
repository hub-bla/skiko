package org.jetbrains.skiko.context

import kotlinx.cinterop.objcPtr
import org.jetbrains.skia.*
import org.jetbrains.skiko.LayerDrawScope
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.redrawer.MacOsMetalRedrawer

/**
 * Metal ContextHandler implementation for macOS.
 */
internal class MacOsMetalContextHandler(layer: SkiaLayer) : GaneshContextHandler(layer) {
    private val metalRedrawer: MacOsMetalRedrawer
        get() = layer.redrawer!! as MacOsMetalRedrawer

    override fun initContext(): Boolean {
        try {
            if (context == null) {
                context = DirectContext.makeMetal(metalRedrawer.device.objcPtr(), metalRedrawer.queue.objcPtr())
            }
        } catch (e: Exception) {
            println("${e.message}\nFailed to create Skia Ganesh Metal context!")
            return false
        }
        return true
    }

    override fun LayerDrawScope.initCanvas() {
        disposeCanvas()

        val w = scaledLayerWidth
        val h = scaledLayerHeight

        if (w > 0 && h > 0) {
            renderTarget = BackendRenderTarget.makeMetal(w, h, metalRedrawer.getDrawableTexture())

            surface = Surface.makeFromBackendRenderTarget(
                context!!,
                renderTarget!!,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = layer.pixelGeometry)
            ) ?: throw RenderException("Cannot create surface")

            canvas = surface!!.canvas
        } else {
            renderTarget = null
            surface = null
            canvas = null
        }
    }

    override fun flush(scope: LayerDrawScope) {
        // TODO: maybe make flush async as in JVM version.
        context?.flush()
        surface?.flushAndSubmit()
        metalRedrawer.finishFrame()
    }

    override fun rendererInfo(): String {
        return "Ganesh Native Metal: device ${metalRedrawer.device.name}"
    }
}

