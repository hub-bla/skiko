package org.jetbrains.skia.gpu.graphite

import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.impl.Managed
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.Stats
import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skia.impl.reachabilityBarrier
import org.jetbrains.skiko.ExperimentalSkikoApi

/**
 * Recreates Graphite pipelines from serialized pipeline keys.
 *
 * Obtain keys using `ContextOptions::fPipelineCachingCallback` in the embedding application and
 * persist them only for a compatible Skia, GPU, and driver configuration.
 */
@ExperimentalSkikoApi
class PrecompileContext internal constructor(ptr: NativePointer) : Managed(ptr, _FinalizerHolder.PTR) {
    /**
     * Precompiles a small baseline of Graphite pipelines for solid rectangles, linear gradients,
     * and clamped BGRA images.
     *
     * This is not a translation of Flutter Impeller's pipeline list. Graphite chooses concrete
     * render-step and coverage variants. Capture and replay application-specific combinations
     * with [precompile].
     */
    fun precompileImpellerLikePipelines(includeMSAA: Boolean = true) {
        try {
            Stats.onNativeCall()
            _nPrecompileImpellerLikePipelines(nativePtr, includeMSAA)
        } finally {
            reachabilityBarrier(this)
        }
    }

    /**
     * Starts recreating the pipeline identified by [serializedPipelineKey].
     *
     * @return `true` when Graphite accepted the key; `false` for an invalid or incompatible key.
     */
    fun precompile(serializedPipelineKey: ByteArray): Boolean {
        if (serializedPipelineKey.isEmpty()) return false

        return try {
            Stats.onNativeCall()
            interopScope {
                _nPrecompile(nativePtr, toInterop(serializedPipelineKey), serializedPipelineKey.size)
            }
        } finally {
            reachabilityBarrier(this)
        }
    }

    private object _FinalizerHolder {
        val PTR = _nGetPrecompileContextFinalizer()
    }
}

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_PrecompileContext__1nGetFinalizer")
private external fun _nGetPrecompileContextFinalizer(): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_PrecompileContext__1nPrecompile")
private external fun _nPrecompile(
    contextPtr: NativePointer,
    serializedPipelineKey: InteropPointer,
    size: Int,
): Boolean

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_PrecompileContext__1nPrecompileImpellerLikePipelines")
private external fun _nPrecompileImpellerLikePipelines(
    contextPtr: NativePointer,
    includeMSAA: Boolean,
)
