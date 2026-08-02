package org.jetbrains.skia.gpu.graphite

import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.use
import org.jetbrains.skiko.ExperimentalSkikoApi
import kotlin.test.Test

@OptIn(ExperimentalSkikoApi::class)
class GraphiteTest {
    @Test
    fun contextCanRecordAndSubmit() {
        val context = makeTestGraphiteContext() ?: return
        context.use { context ->
            context.makeRecorder().use { recorder ->
                recorder.snap().use { recording ->
                    context.insertRecording(recording)
                    context.submit(syncCpu = true)
                }
            }
        }
    }

    @Test
    fun externalBackendTextureCanBeWrapped() {
        useTestExternalBackendTexture { context, backendTexture ->
            context.makeRecorder().use { recorder ->
                Surface.wrapBackendTexture(recorder, backendTexture, ColorSpace.sRGB)?.use { surface ->
                    surface.canvas.clear(Color.RED)
                } ?: error("Failed to wrap external backend texture")
                recorder.snap().use { recording ->
                    context.insertRecording(recording)
                    context.submit(syncCpu = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalSkikoApi::class)
internal expect fun makeTestGraphiteContext(): GraphiteContext?

@OptIn(ExperimentalSkikoApi::class)
internal expect fun useTestExternalBackendTexture(
    block: (GraphiteContext, BackendTexture) -> Unit,
): Boolean
