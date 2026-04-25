package org.jetbrains.skia.gpu.graphite

import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.RefCnt
import org.jetbrains.skia.impl.Stats
import org.jetbrains.skiko.ExperimentalSkikoApi

@ExperimentalSkikoApi
class GraphiteContext constructor(ptr: NativePointer) : RefCnt(ptr) {
    companion object {
        fun makeMetal(devicePtr: NativePointer, queuePtr: NativePointer): GraphiteContext {
            Stats.onNativeCall()
            return GraphiteContext(_nMakeMetal(devicePtr, queuePtr))
        }
    }

    fun makeRecorder(): Recorder {
        return Recorder(_nMakeRecorder(_ptr))
    }

    fun insertRecording(recording: Recording) {
        _nInsertRecording(_ptr, recording._ptr)
    }

    fun submit(syncCpu: Boolean = false) {
        _nSubmit(_ptr, syncCpu)
    }
}

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nMakeMetal")
private external fun _nMakeMetal(devicePtr: NativePointer, queuePtr: NativePointer): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nMakeRecorder")
private external fun _nMakeRecorder(contextPtr: NativePointer): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nInsertRecording")
private external fun _nInsertRecording(
    contextPtr: NativePointer,
    recordingPtr: NativePointer
)

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteContext__1nSubmit")
private external fun _nSubmit(contextPtr: NativePointer, syncCpu: Boolean)
