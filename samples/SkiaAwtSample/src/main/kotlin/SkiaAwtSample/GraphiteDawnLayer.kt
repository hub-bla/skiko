package SkiaAwtSample

import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Surface
import org.jetbrains.skia.gpu.graphite.BackendTexture
import org.jetbrains.skia.gpu.graphite.DawnDirectXDevice
import org.jetbrains.skia.gpu.graphite.GraphiteContext
import org.jetbrains.skia.gpu.graphite.wrapBackendTexture
import org.jetbrains.skia.impl.use
import org.jetbrains.skiko.ExperimentalSkikoApi
import java.awt.Canvas
import java.awt.Graphics
import java.awt.event.MouseMotionListener
import javax.swing.Timer
import kotlin.math.roundToInt

@OptIn(ExperimentalSkikoApi::class)
class GraphiteDawnLayer(private val clocks: ClocksAwt) : Canvas() {
    private lateinit var device: DawnDirectXDevice
    private lateinit var context: GraphiteContext
    private var physicalWidth = 0
    private var physicalHeight = 0

    private val redrawTimer = Timer(16) { repaint() }

    init {
        addMouseMotionListener(clocks as MouseMotionListener)
        redrawTimer.isCoalesce = true
    }

    override fun addNotify() {
        super.addNotify()
        device = DawnDirectXDevice.make(this)
        context = device.makeGraphiteContext()
        redrawTimer.start()
    }

    override fun removeNotify() {
        redrawTimer.stop()
        if (::context.isInitialized && !context.isClosed) context.close()
        if (::device.isInitialized && !device.isClosed) device.close()
        super.removeNotify()
    }

    override fun paint(graphics: Graphics) {
        if (!::context.isInitialized || width <= 0 || height <= 0) return
        val scale = graphicsConfiguration.defaultTransform.scaleX.toFloat()
        val targetWidth = (width * scale).roundToInt()
        val targetHeight = (height * scale).roundToInt()
        if (targetWidth != physicalWidth || targetHeight != physicalHeight) {
            if (physicalWidth != 0) context.submit(syncCpu = true)
            device.resize(targetWidth, targetHeight)
            physicalWidth = targetWidth
            physicalHeight = targetHeight
        }

        try {
            val texture = device.acquireTexture()
            BackendTexture.makeDawn(texture).use { backendTexture ->
                context.makeRecorder().use { recorder ->
                    val surface = checkNotNull(Surface.wrapBackendTexture(recorder, backendTexture, ColorSpace.sRGB)) {
                        "Failed to wrap the Dawn swap chain texture"
                    }
                    surface.use {
                        surface.canvas.scale(scale, scale)
                        clocks.onRender(surface.canvas, width, height, System.nanoTime())
                    }
                    recorder.snap().use { recording ->
                        context.insertRecording(recording)
                        context.submit()
                    }
                }
            }
        } finally {
            device.present()
        }
    }
}
