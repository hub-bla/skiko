package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skiko.Logger
import org.jetbrains.skiko.MetalAdapter
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.redrawer.MetalDevice

/**
 * Provides a way to draw on Skia canvas created in [layer] bounds using Metal GPU acceleration.
 *
 * For each [ContextHandler.draw] request it initializes Skia Canvas with Metal context and
 * draws [SkiaLayer.draw] content in this canvas.
 *
 * @see "src/awtMain/objectiveC/macos/MetalContextHandler.mm" -- native implementation
 */
internal class MetalContextHandler(
    layer: SkiaLayer,
    private val device: MetalDevice,
    private val adapter: MetalAdapter
) : ContextBasedContextHandler(layer, "Metal") {

    companion object {
        /** Set this to System.nanoTime() as the very first line of main() */
        @JvmField var t0LaunchNs: Long = 0L
    }

    private fun msSinceLaunch() = (System.nanoTime() - t0LaunchNs) / 1_000_000

    override fun makeContext(): DirectContext {
        val t = System.nanoTime()
        return DirectContext(makeMetalContext(device.ptr))
    }

    override fun initCanvas() {
        val t = System.nanoTime()
        disposeCanvas()

        val scale = layer.contentScale
        val width = (layer.backedLayer.width * scale).toInt().coerceAtLeast(0)
        val height = (layer.backedLayer.height * scale).toInt().coerceAtLeast(0)

        if (width > 0 && height > 0) {
            renderTarget = makeRenderTarget(width, height)

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

        System.out.flush()
    }

    @Volatile private var producedFrames = 0
    override fun flush() {
        val t0 = System.nanoTime()
        super.flush()                   // calls onRender on your render delegate
        val t1 = System.nanoTime()
        surface?.flushAndSubmit()       // submits GPU commands
        val t2 = System.nanoTime()
        finishFrame()                   // Metal present — frame is now on screen
        val t3 = System.nanoTime()
        producedFrames += 1

        if (producedFrames <= 30) {
            println("SKIKO_PROBE FRAME($producedFrames) super.flush()  took=${(t1 - t0)} ns")
            println("SKIKO_PROBE FRAME($producedFrames) flushAndSubmit() took=${(t2 - t1)} ns")
            println("SKIKO_PROBE FRAME($producedFrames) finishFrame() took=${(t3 - t2)} ns")
            println("SKIKO_PROBE FRAME($producedFrames) total took=${(t3 - t0)} ns")
            System.out.flush()
        }

        Logger.debug { "MetalContextHandler finished drawing frame" }
    }

    override fun rendererInfo(): String {
        return super.rendererInfo() +
                "Video card: ${adapter.name}\n" +
                "Total VRAM: ${adapter.memorySize / 1024 / 1024} MB\n"
    }

    private fun makeRenderTarget(width: Int, height: Int) = BackendRenderTarget(
        makeMetalRenderTarget(device.ptr, width, height)
    )

    private fun finishFrame() = finishFrame(device.ptr)

    private external fun makeMetalContext(device: Long): Long
    private external fun makeMetalRenderTarget(device: Long, width: Int, height: Int): Long
    private external fun finishFrame(device: Long)
}