package org.jetbrains.skiko.swing

import org.jetbrains.skiko.GpuPriority
import org.jetbrains.skiko.InternalSkikoApi
import java.awt.GraphicsConfiguration

@InternalSkikoApi
interface SwingLayerProperties {
    val width: Int

    val height: Int

    val graphicsConfiguration: GraphicsConfiguration

    val adapterPriority: GpuPriority

    val gpuResourceCacheLimit: Long
}

@InternalSkikoApi
val SwingLayerProperties.scale: Float get() = graphicsConfiguration.defaultTransform.scaleX.toFloat()
