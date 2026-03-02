package org.jetbrains.skiko.context

import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.graphite.BackendTexture
import org.jetbrains.skia.graphite.Recorder
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.Logger
import org.jetbrains.skiko.MetalAdapter
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.redrawer.MetalDevice

/**
 * Provides a way to draw on Skia canvas created in [layer] bounds using Metal GPU acceleration.
 *
 * For each [ContextHandler.draw] request it initializes Skia Canvas with Metal context and
 * draws [org.jetbrains.skiko.SkiaLayer.draw] content in this canvas.
 *
 * @see "src/awtMain/objectiveC/macos/MetalContextHandler.mm" -- native implementation
 */
internal class GraphiteMetalContextHandler(
    layer: SkiaLayer,
    private val device: MetalDevice,
    private val adapter: MetalAdapter
) : ContextBasedContextHandler(layer, "Metal") {
    var recorder: Recorder? = null

    var textureInfo: NativePointer? = null
    var imageInfo: NativePointer? = null
    override fun initCanvas() {
        disposeCanvas()

        val scale = layer.contentScale
        val width = (layer.backedLayer.width * scale).toInt().coerceAtLeast(0)
        val height = (layer.backedLayer.height * scale).toInt().coerceAtLeast(0)
        if (width > 0 && height > 0) {
            backendTexture = BackendTexture(createBackendTexture(device.ptr, width, height))
            textureInfo = backendTexture!!.getTextureInfo()
            surface = Surface.makeFromBackendTexture(
                recorder!!,
                backendTexture!!,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = layer.pixelGeometry)
            ) ?: throw RenderException("Cannot create surface")

            canvas = surface!!.canvas
            imageInfo = canvas!!.getImageInfo()
        } else {
            backendTexture = null
            surface = null
            canvas = null
        }
    }

    override fun flush() {
//        super.flush()
//        surface?.flushAndSubmit()
        getDirectContext()?.graphiteSubmit()
        finishFrame()
        Logger.debug { "MetalContextHandler finished drawing frame" }
    }

    override fun rendererInfo(): String {
        return super.rendererInfo() +
                "Video card: ${adapter.name}\n" +
                "Total VRAM: ${adapter.memorySize / 1024 / 1024} MB\n"
    }

    override fun makeContext(): DirectContext {
        val contextPtr = makeMetalContext(device.ptr)
        if (contextPtr != 0L) {
            println("Graphite metal context created")
        }
        recorder = Recorder.makeFromGraphiteContext(contextPtr)
        return DirectContext(
            contextPtr, recorder!!
        )
    }

    override fun dispose() {
        disposeCanvas()
        context?.close()
    }


    fun getTextureInfo() = getTexInfo()
    private fun finishFrame() = finishFrame(device.ptr)

    private external fun makeMetalContext(device: Long): Long
    private external fun createBackendTexture(device: Long, width: Int, height: Int): Long
    private external fun finishFrame(device: Long)

    private external fun getTexInfo() : NativePointer
}