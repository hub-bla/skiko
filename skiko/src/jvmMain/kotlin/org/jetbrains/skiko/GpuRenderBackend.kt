package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.Redrawer
import java.util.ServiceLoader

@InternalSkikoApi
interface GpuRenderBackend {
    val supportedApis: Set<GraphicsApi>

    fun createRedrawer(
        layer: SkiaLayer,
        renderApi: GraphicsApi,
        analytics: SkiaLayerAnalytics,
        properties: SkiaLayerProperties
    ): Redrawer
}

@InternalSkikoApi
object RenderBackendRegistry {
    private val serviceBackends: List<GpuRenderBackend> by lazy {
        ServiceLoader.load(GpuRenderBackend::class.java).toList()
    }

    fun find(renderApi: GraphicsApi): GpuRenderBackend? =
        serviceBackends.firstOrNull { renderApi in it.supportedApis }
}
