package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skia.graphite.BackendTexture
import org.jetbrains.skiko.*

internal abstract class ContextHandler(
    protected val layer: SkiaLayer,
    private val drawContent: Canvas.() -> Unit,
    private val drawGraphiteContent : DirectContext.(surface: Surface) -> Unit = {  }
) {
    protected var context: DirectContext? = null
    protected var renderTarget: BackendRenderTarget? = null
    protected var backendTexture: BackendTexture? = null
    protected var surface: Surface? = null
    protected var canvas: Canvas? = null

    protected abstract fun initContext(): Boolean
    protected abstract fun initCanvas()

    protected open fun flush() {
        context?.flush()
    }

    open fun dispose() {
        disposeCanvas()
        context?.close()
    }

    protected open fun disposeCanvas() {
        surface?.close()
        backendTexture?.close()
    }

    open fun rendererInfo(): String {
        return "GraphicsApi: ${layer.renderApi}\n" +
                "OS: ${hostOs.id} ${hostArch.id}\n"
    }

    // throws RenderException if initialization of graphic context was not successful
    fun draw() {
        if (!initContext()) {
            throw RenderException("Cannot init graphic context")
        }
        initCanvas()
        canvas?.apply {
            clear(Color.MAGENTA)

            val scale = layer.contentScale
            for (clip in layer.cutoutRectangles) {
                cutoutFromClip(clip, scale)
            }

            val layerBg = layer.backgroundColor
            clear(
                if (layer.transparency && isTransparentBackgroundSupported()) {
                    layerBg
                } else {
                    layerBg or 0xFF000000.toInt()
                }
            )

//            clear(Color.MAGENTA)

        }

        context?.apply {
            insertRecording() //  apply above changes
            drawGraphiteContent(surface!!) // insert the recording from skia Layer
            graphiteSubmit() // send work to GPU
        }
        flush()
    }

    protected open fun isTransparentBackgroundSupported(): Boolean {
        if (hostOs == OS.MacOS) {
            // macOS transparency is always supported
            return true
        }

        // for non-macOS in fullscreen transparency is not supported
        return !layer.fullscreen
    }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Canvas.cutoutFromClip(rectangle: ClipRectangle, scale: Float) {
    clipRect(
        left = rectangle.x * scale,
        top = rectangle.y * scale,
        right = (rectangle.x + rectangle.width) * scale,
        bottom = (rectangle.y + rectangle.height) * scale,
        mode = ClipMode.DIFFERENCE,
        antiAlias = true
    )
}