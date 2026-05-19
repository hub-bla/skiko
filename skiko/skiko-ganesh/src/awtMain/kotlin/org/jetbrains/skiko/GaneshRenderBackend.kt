package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.AngleRedrawer
import org.jetbrains.skiko.redrawer.Direct3DRedrawer
import org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawer
import org.jetbrains.skiko.redrawer.MetalRedrawer
import org.jetbrains.skiko.redrawer.Redrawer
import org.jetbrains.skiko.redrawer.WindowsOpenGLRedrawer

@InternalSkikoApi
class GaneshRenderBackend : GpuRenderBackend {
    override val supportedApis: Set<GraphicsApi> = setOf(
        GraphicsApi.METAL,
        GraphicsApi.OPENGL,
        GraphicsApi.ANGLE,
        GraphicsApi.DIRECT3D
    )

    override fun createRedrawer(
        layer: SkiaLayer,
        renderApi: GraphicsApi,
        analytics: SkiaLayerAnalytics,
        properties: SkiaLayerProperties
    ): Redrawer = when (hostOs) {
        OS.MacOS -> MetalRedrawer(layer, analytics, properties)
        OS.Windows -> when (renderApi) {
            GraphicsApi.OPENGL -> WindowsOpenGLRedrawer(layer, analytics, properties)
            GraphicsApi.ANGLE -> AngleRedrawer(layer, analytics, properties)
            else -> Direct3DRedrawer(layer, analytics, properties)
        }
        OS.Linux -> LinuxOpenGLRedrawer(layer, analytics, properties)
        else -> throw UnsupportedOperationException("Ganesh AWT backend doesn't support $hostOs")
    }
}
