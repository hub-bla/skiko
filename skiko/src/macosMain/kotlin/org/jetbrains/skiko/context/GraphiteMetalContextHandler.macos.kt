package org.jetbrains.skiko.context

import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import org.jetbrains.skia.*
import org.jetbrains.skia.graphite.BackendTexture
import org.jetbrains.skia.graphite.Recorder
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.redrawer.MacOsMetalRedrawer
import kotlin.time.TimeSource


/**
 * Metal ContextHandler implementation for MacOs.
 */
internal class MacOsGraphiteMetalContextHandler(layer: SkiaLayer) : ContextHandler(layer, layer::draw) {
    private val metalRedrawer: MacOsMetalRedrawer
        get() = layer.redrawer!! as MacOsMetalRedrawer

    private var recorder: Recorder? = null
    private var backendTexture: BackendTexture? = null
    override fun initContext(): Boolean {
        try {
            if (context == null) {
                context = metalRedrawer.makeContext()
                recorder = Recorder.makeFromGraphiteContext(context!!).also {
                    context!!.recorder = it
                }
            }
        } catch (e: Exception) {
            println("${e.message}\nFailed to create Skia Metal context!")
            return false
        }
        return true
    }

    override fun initCanvas() {
        disposeCanvas()

        val scale = layer.contentScale
        val w = (layer.nsView.frame.useContents { size.width } * scale).toInt().coerceAtLeast(0)
        val h = (layer.nsView.frame.useContents { size.height } * scale).toInt().coerceAtLeast(0)

        if (w > 0 && h > 0) {
            val drawable = metalRedrawer.getNextDrawable()
            backendTexture = BackendTexture.wrapMetalTexture(drawable!!.texture.objcPtr(), w, h)
            surface =  Surface.makeFromBackendTexture(
                recorder!!,
                backendTexture!!,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN)
            )
                ?: throw RenderException("Cannot create surface")

            canvas = surface!!.canvas
        } else {
            backendTexture = null
            surface = null
            canvas = null
        }
    }

    override fun flush() {
        super.flush()
        surface?.flushAndSubmit()
        metalRedrawer.finishFrame()
    }

    override fun rendererInfo(): String {
        return "Graphite Native Metal: device ${metalRedrawer.device.name}"
    }
}

