package org.jetbrains.skiko.swing

import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.InternalSkikoApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.hostOs

@InternalSkikoApi
class GaneshSwingRenderBackend : SwingRenderBackend {
    override val supportedApis: Set<GraphicsApi> = setOf(
        GraphicsApi.METAL,
        GraphicsApi.OPENGL,
        GraphicsApi.ANGLE,
        GraphicsApi.DIRECT3D
    )

    override fun createSwingRedrawer(
        swingLayerProperties: SwingLayerProperties,
        renderDelegate: SkikoRenderDelegate,
        renderApi: GraphicsApi,
        analytics: SkiaLayerAnalytics
    ): SwingRedrawer = when (hostOs) {
        OS.MacOS -> MetalSwingRedrawer(swingLayerProperties, renderDelegate, analytics)
        OS.Windows -> Direct3DSwingRedrawer(swingLayerProperties, renderDelegate, analytics)
        OS.Linux -> LinuxOpenGLSwingRedrawer(swingLayerProperties, renderDelegate, analytics)
        else -> throw UnsupportedOperationException("Ganesh Swing backend doesn't support $hostOs")
    }
}
