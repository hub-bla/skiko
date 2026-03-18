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

    private var producedFrames = 0
    override fun flush() {
        val timeSource = TimeSource.Monotonic

        val m0 = timeSource.markNow()
        super.flush()
        val m1 = timeSource.markNow()

        surface?.flushAndSubmit()
        val m2 = timeSource.markNow()

        metalRedrawer.finishFrame()
        val m3 = timeSource.markNow()

        producedFrames += 1

        if (producedFrames <= 30) {
            val t10 = (m1 - m0).inWholeNanoseconds
            val t21 = (m2 - m1).inWholeNanoseconds
            val t32 = (m3 - m2).inWholeNanoseconds
            val total = (m3 - m0).inWholeNanoseconds

            println("SKIKO_PROBE FRAME($producedFrames) super.flush()  took=$t10 ns")
            println("SKIKO_PROBE FRAME($producedFrames) flushAndSubmit() took=$t21 ns")
            println("SKIKO_PROBE FRAME($producedFrames) finishFrame() took=$t32 ns")
            println("SKIKO_PROBE FRAME($producedFrames) total took=$total ns")
        }
    }

    override fun rendererInfo(): String {
        return "Graphite Native Metal: device ${metalRedrawer.device.name}"
    }
}

