package org.jetbrains.skia.gpu.graphite

import org.jetbrains.skia.impl.use
import org.jetbrains.skiko.ExperimentalSkikoApi
import kotlin.test.assertFalse
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
    fun precompileContextRejectsInvalidPipelineKey() {
        val context = makeTestGraphiteContext() ?: return
        context.use {
            it.makePrecompileContext().use { precompileContext ->
                assertFalse(precompileContext.precompile(byteArrayOf(0)))
            }
        }
    }

    @Test
    fun precompileImpellerLikePipelines() {
        val context = makeTestGraphiteContext() ?: return
        context.use {
            it.makePrecompileContext().use { precompileContext ->
                precompileContext.precompileImpellerLikePipelines(includeMSAA = false)
            }
        }
    }
}

@OptIn(ExperimentalSkikoApi::class)
internal expect fun makeTestGraphiteContext(): GraphiteContext?
