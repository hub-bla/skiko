package org.jetbrains.skia

import org.jetbrains.skia.impl.*

fun Surface.Companion.makeFromBackendRenderTarget(
    context: DirectContext,
    rt: BackendRenderTarget,
    origin: SurfaceOrigin,
    colorFormat: SurfaceColorFormat,
    colorSpace: ColorSpace?,
    surfaceProps: SurfaceProps? = null
): Surface? {
    return try {
        Stats.onNativeCall()
        val ptr = interopScope {
            Surface_nMakeFromBackendRenderTarget(
                getPtr(context),
                getPtr(rt),
                origin.ordinal,
                colorFormat.ordinal,
                getPtr(colorSpace),
                toInterop(surfaceProps?.packToIntArray())
            )
        }
        if (ptr == Native.NullPointer) null else Surface(ptr)
    } finally {
        reachabilityBarrier(context)
        reachabilityBarrier(rt)
        reachabilityBarrier(colorSpace)
    }
}

fun Surface.flushAndSubmit() {
    recordingContext?.flushAndSubmit(this)
}

fun Surface.flushAndSubmit(syncCpu: Boolean) {
    recordingContext?.flushAndSubmit(this, syncCpu)
}

fun Surface.flush() {
    recordingContext?.flush(this)
}

val Surface.recordingContext: DirectContext?
    get() = try {
        Stats.onNativeCall()
        val ptr = Surface_nGetRecordingContext(_ptr)
        if (ptr == Native.NullPointer) null else DirectContext(ptr)
    } finally {
        reachabilityBarrier(this)
    }

@ExternalSymbolName("org_jetbrains_skia_Surface__1nMakeFromBackendRenderTarget")
private external fun Surface_nMakeFromBackendRenderTarget(
    pContext: NativePointer,
    pBackendRenderTarget: NativePointer,
    surfaceOrigin: Int,
    colorType: Int,
    colorSpacePtr: NativePointer,
    surfaceProps: InteropPointer
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_Surface__1nGetRecordingContext")
private external fun Surface_nGetRecordingContext(ptr: NativePointer): NativePointer
