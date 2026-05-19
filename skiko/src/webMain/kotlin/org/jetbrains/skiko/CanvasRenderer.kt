package org.jetbrains.skiko

import kotlinx.browser.window
import org.jetbrains.skia.*
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skiko.wasm.ContextAttributes
import org.w3c.dom.HTMLCanvasElement

/**
 * CanvasRenderer takes an [HTMLCanvasElement] instance and initializes
 * skiko's [Canvas] used for drawing (see [initCanvas]).
 *
 * After initialization [needRedraw] can be used to schedule a call to [drawFrame].
 * [drawFrame] has to be implemented to perform the actual drawing on [canvas].
 */
internal abstract class CanvasRenderer(
    private val contextPointer: NativePointer,
    val width: Int,
    val height: Int,
) {
    private var context: NativePointer = 0
    private var surface: Surface? = null
    private var renderTarget: NativePointer = 0

    /**
     * An instance of skiko [Canvas] used for drawing.
     * Created in [initCanvas].
     */
    protected var canvas: Canvas? = null
        private set

    init {
        GL.makeContextCurrent(contextPointer)
        context = _nMakeGL()
        initCanvas()
    }

    fun initCanvas() {
        disposeCanvas()

        renderTarget = _nMakeBackendRenderTargetGL(width, height, 1, 8, 0, 0x8058)
        val surfacePtr = interopScope {
            _nMakeFromBackendRenderTarget(
                context,
                renderTarget,
                SurfaceOrigin.BOTTOM_LEFT.ordinal,
                SurfaceColorFormat.RGBA_8888.ordinal,
                getPtr(ColorSpace.sRGB),
                toInterop(SurfaceProps().packToIntArray())
            )
        }
        if (surfacePtr == 0) throw RenderException("Cannot create surface")
        surface = Surface(surfacePtr)
        canvas = surface!!.canvas
    }

    private fun disposeCanvas() {
        surface?.close()
        surface = null
        if (renderTarget != 0) {
            _nDeleteBackendRenderTarget(renderTarget)
            renderTarget = 0
        }
    }

    /**
     * This function should implement the actual drawing on the canvas.
     *
     * @param currentTimestamp - in milliseconds
     */
    abstract fun drawFrame(currentTimestamp: Double)

    private var redrawScheduled = false

    /**
     * Schedules a call to [drawFrame] to the appropriate moment.
     */
    fun needRedraw() {
        if (redrawScheduled) {
            return
        }
        redrawScheduled = true
        window.requestAnimationFrame { timestamp ->
            redrawScheduled = false
            GL.makeContextCurrent(contextPointer)
            // `clear` and `resetMatrix` make canvas not accumulate previous effects
            canvas?.clear(Color.WHITE)
            canvas?.resetMatrix()
            drawFrame(timestamp)
            surface?.let { _nFlushAndSubmit(context, it._ptr, false) }
            _nFlushDefault(context)
        }
    }
}

private external fun _nMakeGL(): NativePointer
private external fun _nMakeBackendRenderTargetGL(width: Int, height: Int, sampleCnt: Int, stencilBits: Int, fbId: Int, fbFormat: Int): NativePointer
private external fun _nDeleteBackendRenderTarget(ptr: NativePointer)
private external fun _nMakeFromBackendRenderTarget(
    pContext: NativePointer,
    pBackendRenderTarget: NativePointer,
    surfaceOrigin: Int,
    colorType: Int,
    colorSpacePtr: NativePointer,
    surfaceProps: InteropPointer
): NativePointer
private external fun _nFlushAndSubmit(contextPtr: NativePointer, surfacePtr: NativePointer, syncCpu: Boolean)
private external fun _nFlushDefault(contextPtr: NativePointer)

internal external interface GLInterface {
    fun createContext(context: HTMLCanvasElement, contextAttributes: ContextAttributes): NativePointer
    fun makeContextCurrent(contextPointer: NativePointer): Boolean;
}

internal expect val GL: GLInterface
