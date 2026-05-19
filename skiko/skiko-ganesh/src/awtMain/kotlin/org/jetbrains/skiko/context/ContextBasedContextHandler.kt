package org.jetbrains.skiko.context

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skiko.Logger
import org.jetbrains.skiko.SkiaLayer

internal abstract class ContextBasedContextHandler(layer: SkiaLayer, val name: String) : JvmContextHandler(layer) {
    protected var context: DirectContext? = null
    protected var renderTarget: BackendRenderTarget? = null

    protected abstract fun makeContext(): DirectContext

    override fun initContext(): Boolean {
        try {
            if (context == null) {
                context = makeContext()
                onContextInitialized()
                val limit = layer.properties.gpuResourceCacheLimit
                if (limit >= 0) {
                    context?.resourceCacheLimit = limit
                }
            }
        } catch (e: Exception) {
            Logger.warn(e) { "Failed to create Skia $name context!" }
            return false
        }
        return true
    }

    override fun flush(scope: org.jetbrains.skiko.LayerDrawScope) {
        context?.flush()
    }

    override fun dispose() {
        super.dispose()
        context?.close()
    }

    override fun disposeCanvas() {
        super.disposeCanvas()
        renderTarget?.close()
    }
}
