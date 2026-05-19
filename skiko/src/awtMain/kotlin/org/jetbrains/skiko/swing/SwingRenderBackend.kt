package org.jetbrains.skiko.swing

import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.InternalSkikoApi
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkikoRenderDelegate
import java.util.ServiceLoader

@InternalSkikoApi
interface SwingRenderBackend {
    val supportedApis: Set<GraphicsApi>

    fun createSwingRedrawer(
        swingLayerProperties: SwingLayerProperties,
        renderDelegate: SkikoRenderDelegate,
        renderApi: GraphicsApi,
        analytics: SkiaLayerAnalytics
    ): SwingRedrawer
}

@InternalSkikoApi
object SwingRenderBackendRegistry {
    private val serviceBackends: List<SwingRenderBackend> by lazy {
        ServiceLoader.load(SwingRenderBackend::class.java).toList()
    }

    fun find(renderApi: GraphicsApi): SwingRenderBackend? =
        serviceBackends.firstOrNull { renderApi in it.supportedApis }
}
