package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.*
import javax.swing.UIManager

actual fun setSystemLookAndFeel() = UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

internal actual fun makeDefaultRenderFactory(): RenderFactory =
    RenderFactory { layer, renderApi, analytics, properties ->
        when (hostOs) {
            OS.MacOS -> when (renderApi) {
                GraphicsApi.SOFTWARE_COMPAT, GraphicsApi.SOFTWARE_FAST -> SoftwareRedrawer(layer, analytics, properties)
                else -> ganeshRedrawer(layer, renderApi, analytics, properties)
            }
            OS.Windows -> when (renderApi) {
                GraphicsApi.SOFTWARE_COMPAT -> SoftwareRedrawer(layer, analytics, properties)
                GraphicsApi.SOFTWARE_FAST -> WindowsSoftwareRedrawer(layer, analytics, properties)
                else -> ganeshRedrawer(layer, renderApi, analytics, properties)
            }
            OS.Linux -> when (renderApi) {
                GraphicsApi.SOFTWARE_COMPAT -> SoftwareRedrawer(layer, analytics, properties)
                GraphicsApi.SOFTWARE_FAST -> LinuxSoftwareRedrawer(layer, analytics, properties)
                else -> ganeshRedrawer(layer, renderApi, analytics, properties)
            }
            else -> throw UnsupportedOperationException("AWT doesn't support $hostOs")
        }
    }

@OptIn(InternalSkikoApi::class)
private fun ganeshRedrawer(
    layer: SkiaLayer,
    renderApi: GraphicsApi,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) = RenderBackendRegistry.find(renderApi)?.createRedrawer(layer, renderApi, analytics, properties)
    ?: throw RenderException(
        "Renderer $renderApi requires the org.jetbrains.skiko:skiko-ganesh dependency on the classpath."
    )
