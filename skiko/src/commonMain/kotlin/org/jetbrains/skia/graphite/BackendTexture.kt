package org.jetbrains.skia.graphite

import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.Managed
import org.jetbrains.skia.impl.NativePointer

class BackendTexture internal constructor(ptr: NativePointer) : Managed(ptr, _FinalizerHolder.PTR) {
    companion object {
        fun wrapMetalTexture(texturePtr: NativePointer, width: Int, height: Int) =
            BackendTexture(BackendTexture_nWrapMetalTexture(texturePtr, width, height))
    }

    private object _FinalizerHolder {
        val PTR = BackendTexture_nGetFinalizer()
    }
}

@ExternalSymbolName("org_jetbrains_skia_graphite_BackendTexture__1nGetFinalizer")
private external fun BackendTexture_nGetFinalizer(): NativePointer

@ExternalSymbolName("jetbrains_skia_graphite_BackendTexture__1nWrapMetalTexture")
private external fun BackendTexture_nWrapMetalTexture(texturePtr: NativePointer, width: Int, height: Int): NativePointer
