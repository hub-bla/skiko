package org.jetbrains.skiko

import android.content.Context
import android.view.*
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Color

actual open class SkiaLayer {
    private var renderView: AndroidSkikoRenderView? = null
    private var container: ViewGroup? = null

    actual var renderApi: GraphicsApi = GraphicsApi.OPENGL
    actual val contentScale: Float
        get() = container?.context?.resources?.displayMetrics?.density?: 1.0f

    actual var fullscreen: Boolean
        get() = true
        set(value) {
            if (value) throw IllegalArgumentException("changing fullscreen is unsupported")
        }

    actual var renderDelegate: SkikoRenderDelegate? = null

    actual fun attachTo(container: Any) {
        when (container) {
            is ViewGroup -> {
                attachTo(container)
            }
            else -> error("Cannot attach to $container")
        }
    }

    fun attachTo(container: ViewGroup) {
        initDefaultContext(container.context)

        val renderView = createAndroidRenderView(container.context)
        container.addView(renderView.view)

        this.container = container
        this.renderView = renderView

        renderView.view.setFocusableInTouchMode(true)

        needRender()
    }

    actual fun detach() {
        this.container?.let {
            it.removeView(this.renderView?.view)
            this.renderView = null
        }
    }

    actual fun needRender(throttledToVsync: Boolean) {
        renderView?.scheduleFrame()
    }

    actual fun needRedraw() = needRender()

    actual val pixelGeometry: PixelGeometry
        get() = PixelGeometry.UNKNOWN

    actual val component: Any?
        get() = this.container

    internal actual fun draw(canvas: Canvas): Unit = TODO()

    private fun createAndroidRenderView(context: Context): AndroidSkikoRenderView {
        val renderViewClass = try {
            Class.forName("org.jetbrains.skiko.SkikoSurfaceView")
        } catch (e: ClassNotFoundException) {
            throw UnsupportedOperationException(
                "Android GPU rendering is provided by the skiko-ganesh extension module",
                e
            )
        }
        val constructor = renderViewClass.getConstructor(Context::class.java, SkiaLayer::class.java)
        return constructor.newInstance(context, this) as AndroidSkikoRenderView
    }
}

@InternalSkikoApi
interface AndroidSkikoRenderView {
    val view: View

    fun scheduleFrame()
}
