package org.jetbrains.skia.graphite

import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.Managed
import org.jetbrains.skia.impl.NativePointer

class Recorder internal constructor(ptr: NativePointer) : Managed(ptr, _FinalizerHolder.PTR) {
    private object _FinalizerHolder {
        val PTR = Recorder_nGetFinalizer()
    }
}

@ExternalSymbolName("org_jetbrains_skia_graphite_Recorder__1nGetFinalizer")
private external fun Recorder_nGetFinalizer(): NativePointer
