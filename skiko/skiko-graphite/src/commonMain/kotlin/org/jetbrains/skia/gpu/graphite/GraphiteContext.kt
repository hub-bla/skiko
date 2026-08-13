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
 * The main entry point for Graphite, responsible for managing and coordinating GPU resources.
 */
@ExperimentalSkikoApi
class GraphiteContext internal constructor(ptr: NativePointer) : Managed(ptr, _FinalizerHolder.PTR) {
    companion object {
        init {
            GraphiteLibrary.load()
        }

        /**
         * Creates a Graphite context that submits work to a Metal command queue.
         *
         * @param devicePtr native pointer to the Metal device.
         * @param queuePtr native pointer to the Metal command queue.
         * @return a Graphite context backed by Metal.
         */
        fun makeMetal(devicePtr: NativePointer, queuePtr: NativePointer, path: String = ""): GraphiteContext {
            requireMetalSupport()
            require(devicePtr != NullPointer) { "Metal device pointer is null" }
            require(queuePtr != NullPointer) { "Metal queue pointer is null" }
            Stats.onNativeCall()
            val ptr = interopScope { _nMakeMetal(devicePtr, queuePtr, toInterop(path)) }
            check(ptr != NullPointer) { "Failed to create a Graphite Metal context" }
            return GraphiteContext(ptr)
        }
    }

    /**
     * Creates a [Recorder] that records drawing commands for this context.
     *
     * @return a new recorder.
     */
    fun makeRecorder(): Recorder {
        return try {
            Stats.onNativeCall()
            val ptr = _nMakeRecorder(nativePtr)
            check(ptr != NullPointer) { "Failed to create a Graphite recorder" }
            Recorder(ptr)
        } finally {
            reachabilityBarrier(this)
        }
    }

    /**
     * Creates a context that can recreate pipelines from serialized Graphite pipeline keys.
     *
     * The returned context can be moved to a worker thread. Keys must have been produced by
     * Graphite's pipeline caching callback for a compatible Skia and GPU configuration.
     */
    fun makePrecompileContext(): PrecompileContext {
        return try {
            Stats.onNativeCall()
            val ptr = _nMakePrecompileContext(nativePtr)
            check(ptr != NullPointer) { "Failed to create a Graphite precompile context" }
            PrecompileContext(ptr)
        } finally {
            reachabilityBarrier(this)
        }
    }

    /**
     * Adds a [recording] to this context's pending GPU work.
     *
     * The work is sent to the GPU by a subsequent call to [submit].
     *
     * @param recording recording to insert.
     */
    fun insertRecording(recording: Recording) {
        try {
            Stats.onNativeCall()
            _nInsertRecording(nativePtr, recording.nativePtr)
        } finally {
            reachabilityBarrier(this)
            reachabilityBarrier(recording)
        }
    }

    /**
     * Submits pending work to the GPU.
     *
     * @param syncCpu if `true`, waits for the submitted GPU work to finish before returning.
     */
    fun submit(syncCpu: Boolean = false) {
        try {
            Stats.onNativeCall()
            _nSubmit(nativePtr, syncCpu)
        } finally {
            reachabilityBarrier(this)
        }
    }

    private object _FinalizerHolder {
        val PTR = _nGetGraphiteContextFinalizer()
    }
}

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nGetFinalizer")
private external fun _nGetGraphiteContextFinalizer(): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nMakeMetal")
private external fun _nMakeMetal(
    devicePtr: NativePointer,
    queuePtr: NativePointer,
    path: InteropPointer,
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nMakeRecorder")
private external fun _nMakeRecorder(contextPtr: NativePointer): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nMakePrecompileContext")
private external fun _nMakePrecompileContext(contextPtr: NativePointer): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nInsertRecording")
private external fun _nInsertRecording(contextPtr: NativePointer, recordingPtr: NativePointer)

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nSubmit")
private external fun _nSubmit(contextPtr: NativePointer, syncCpu: Boolean)
