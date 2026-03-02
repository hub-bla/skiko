package org.jetbrains.skia.graphite
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.Managed
import org.jetbrains.skia.impl.NativePointer

class Recording internal constructor(ptr: NativePointer) : Managed(ptr, _FinalizerHolder.PTR) {
    private object _FinalizerHolder {
        val PTR = Recording_nGetFinalizer()
    }
}

@ExternalSymbolName("org_jetbrains_skia_graphite_Recording__1nGetFinalizer")
private external fun Recording_nGetFinalizer(): NativePointer

