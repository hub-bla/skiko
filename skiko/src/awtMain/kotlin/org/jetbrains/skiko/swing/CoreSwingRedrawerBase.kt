package org.jetbrains.skiko.swing

import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.InternalSkikoApi
import org.jetbrains.skiko.Logger
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.Version
import org.jetbrains.skiko.hostArch
import org.jetbrains.skiko.hostOs
import java.awt.Graphics2D
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities

@OptIn(ExperimentalSkikoApi::class)
@InternalSkikoApi
abstract class CoreSwingRedrawerBase(
    protected val swingLayerProperties: SwingLayerProperties,
    private val analytics: SkiaLayerAnalytics,
    private val graphicsApi: GraphicsApi
) : SwingRedrawer {
    private var isFirstFrameRendered = false
    private val rendererAnalytics = analytics.renderer(Version.skiko, hostOs, graphicsApi)
    private var deviceAnalytics: SkiaLayerAnalytics.DeviceAnalytics? = null
    private var isDisposed = false

    init { rendererAnalytics.init() }

    protected abstract fun onRender(g: Graphics2D, width: Int, height: Int, nanoTime: Long)

    override fun dispose() {
        require(!isDisposed) { "$javaClass is disposed" }
        isDisposed = true
    }

    final override fun redraw(g: Graphics2D) {
        require(!isDisposed) { "$javaClass is disposed" }
        inDrawScope {
            val scale = swingLayerProperties.scale
            val width = (swingLayerProperties.width * scale).toInt().coerceAtLeast(0)
            val height = (swingLayerProperties.height * scale).toInt().coerceAtLeast(0)
            onRender(g, width, height, System.nanoTime())
        }
    }

    protected fun onDeviceChosen(deviceName: String?) {
        require(!isDisposed) { "$javaClass is disposed" }
        require(deviceAnalytics == null) { "deviceAnalytics is not null" }
        rendererAnalytics.deviceChosen()
        deviceAnalytics = analytics.device(Version.skiko, hostOs, graphicsApi, deviceName)
        deviceAnalytics?.init()
    }

    protected open fun rendererInfo(): String =
        "GraphicsApi: ${graphicsApi}\nOS: ${hostOs.id} ${hostArch.id}\n"

    protected fun onContextInit() {
        require(!isDisposed) { "$javaClass is disposed" }
        requireNotNull(deviceAnalytics) { "deviceAnalytics is not null. Call onDeviceChosen after choosing the drawing device" }
        if (System.getProperty("skiko.hardwareInfo.enabled") == "true") {
            Logger.info { "Renderer info:\n ${rendererInfo()}" }
        }
        deviceAnalytics?.contextInit()
    }

    private inline fun inDrawScope(body: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        requireNotNull(deviceAnalytics) { "deviceAnalytics is not null. Call onDeviceChosen after choosing the drawing device" }
        if (!isDisposed) {
            val isFirstFrame = !isFirstFrameRendered
            isFirstFrameRendered = true
            if (isFirstFrame) deviceAnalytics?.beforeFirstFrameRender()
            try { body() } catch (_: CancellationException) {}
            if (isFirstFrame && !isDisposed) deviceAnalytics?.afterFirstFrameRender()
        }
    }
}

