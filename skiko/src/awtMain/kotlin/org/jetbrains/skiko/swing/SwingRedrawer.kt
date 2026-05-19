package org.jetbrains.skiko.swing

import org.jetbrains.skiko.*
import java.awt.Graphics2D

/**
 * Provides an interface for requesting content to be drawn on a [java.awt.Graphics2D].
 *
 * See [org.jetbrains.skiko.redrawer.Redrawer] redrawer for on-screen rendering
 */
@InternalSkikoApi
interface SwingRedrawer {
    /**
     * Should be called when [SwingRedrawer] no longer needed to free native resources
     */
    fun dispose()

    /**
     * Draw content synchronously on given [java.awt.Graphics2D].
     * Content will be drawn off-screen using Skia engine and then passed to [java.awt.Graphics2D]
     */
    fun redraw(g: Graphics2D)
}

/**
 * Creates a [SwingRedrawer] that will draw content provided by [renderDelegate]
 */
@InternalSkikoApi
fun createSwingRedrawer(
    swingLayerProperties: SwingLayerProperties,
    renderDelegate: SkikoRenderDelegate,
    renderApi: GraphicsApi,
    analytics: SkiaLayerAnalytics,
): SwingRedrawer {
    if (renderApi == GraphicsApi.SOFTWARE_COMPAT || renderApi == GraphicsApi.SOFTWARE_FAST) {
        return SoftwareSwingRedrawer(
            swingLayerProperties,
            renderDelegate,
            analytics
        )
    }
    return SwingRenderBackendRegistry.find(renderApi)
        ?.createSwingRedrawer(swingLayerProperties, renderDelegate, renderApi, analytics)
        ?: throw RenderException(
            "Swing renderer $renderApi requires the org.jetbrains.skiko:skiko-ganesh dependency on the classpath."
        )
}
