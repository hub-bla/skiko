package org.jetbrains.skia.graphite

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.Managed
import org.jetbrains.skia.impl.NativePointer

class Recorder internal constructor(ptr: NativePointer) : Managed(ptr, _FinalizerHolder.PTR) {
    companion object {
        fun makeFromGraphiteContext(contextPtr: NativePointer) = Recorder(Recorder_nMakeFromContext(contextPtr))
        fun makeFromGraphiteContext(context: DirectContext): Recorder {
            return Recorder(Recorder_nMakeFromContext(context._ptr))
        }
    }

    fun snap(): Recording {
        return Recording(Recorder_nSnap(_ptr))
    }

    fun makeDeferredCanvas(texInfo: NativePointer, width: Int, height: Int): Canvas {
        return Canvas(Recorder_nMakeDeferredCanvas(_ptr, texInfo, width, height), false, this)
    }

    private object _FinalizerHolder {
        val PTR = Recorder_nGetFinalizer()
    }
}

@ExternalSymbolName("org_jetbrains_skia_graphite_Recorder__1nSnap")
private external fun Recorder_nSnap(ptr: NativePointer): NativePointer

//
@ExternalSymbolName("org_jetbrains_skia_graphite_Recorder__1nMakeDeferredCanvas")
private external fun Recorder_nMakeDeferredCanvas(
    ptr: NativePointer,
    texInfo: NativePointer,
    width: Int,
    height: Int
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_graphite_Recorder__1nGetFinalizer")
private external fun Recorder_nGetFinalizer(): NativePointer

@ExternalSymbolName("org_jetbrains_skia_graphite_Recorder__1nMakeFromContext")
private external fun Recorder_nMakeFromContext(contextPtr: NativePointer): NativePointer
