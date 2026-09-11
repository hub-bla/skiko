package org.jetbrains.skia.gpu.ganesh

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.GaneshLibrary
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.Native.Companion.NullPointer
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.Stats
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skia.impl.reachabilityBarrier

fun Bitmap.Companion.makeFromImage(image: Image, context: DirectContext): Bitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(image.imageInfo)
    return if (image.readPixels(context, bitmap)) bitmap else {
        bitmap.close()
        throw RuntimeException("Failed to readPixels from $image")
    }
}

val Canvas.recordingContext: DirectContext?
    get() = try {
        GaneshLibrary.load()
        Stats.onNativeCall()
        val ptr = _nGetCanvasRecordingContext(getPtr(this))
        if (ptr == NullPointer) null else DirectContext(ptr, managed = false)
    } finally {
        reachabilityBarrier(this)
    }

val Surface.recordingContext: DirectContext?
    get() = try {
        GaneshLibrary.load()
        Stats.onNativeCall()
        val ptr = _nGetSurfaceRecordingContext(getPtr(this))
        if (ptr == NullPointer) null else DirectContext(ptr, managed = false)
    } finally {
        reachabilityBarrier(this)
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

@ExternalSymbolName("org_jetbrains_skia_gpu_ganesh_ContextExtensions__1nGetCanvasRecordingContext")
private external fun _nGetCanvasRecordingContext(ptr: NativePointer): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_ganesh_ContextExtensions__1nGetSurfaceRecordingContext")
private external fun _nGetSurfaceRecordingContext(ptr: NativePointer): NativePointer