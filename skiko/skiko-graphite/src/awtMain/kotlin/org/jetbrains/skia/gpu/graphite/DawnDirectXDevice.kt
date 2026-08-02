package org.jetbrains.skia.gpu.graphite

import java.awt.Canvas
import org.jetbrains.skia.impl.Managed
import org.jetbrains.skia.impl.Native.Companion.NullPointer
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.Stats
import org.jetbrains.skia.impl.reachabilityBarrier
import org.jetbrains.skiko.ExperimentalSkikoApi

/**
 * Owns the Dawn D3D12 device and the DXGI swap chain used to present an AWT [Canvas].
 *
 * The current backbuffer is exposed as a non-owning [WGPUTexture]. It stays valid until
 * [present] is called, so callers must finish recording and submitting their work first.
 */
@ExperimentalSkikoApi
class DawnDirectXDevice private constructor(private val pointer: NativePointer) : Managed(pointer, _FinalizerHolder.PTR) {
    companion object {
        init {
            GraphiteLibrary.load()
        }

        fun make(canvas: Canvas): DawnDirectXDevice {
            require(canvas.isDisplayable) { "Canvas must have a native peer" }
            Stats.onNativeCall()
            val ptr = _nMake(canvas)
            check(ptr != NullPointer) { "Failed to create a Dawn Direct3D device" }
            return DawnDirectXDevice(ptr)
        }
    }

    fun makeGraphiteContext(): GraphiteContext = withPtr { ptr ->
        val context = _nMakeGraphiteContext(ptr)
        check(context != NullPointer) { "Failed to create a Graphite Dawn context" }
        GraphiteContext(context)
    }

    /** Recreates the swap chain buffers when the physical canvas size changes. */
    fun resize(width: Int, height: Int) = withPtr { ptr ->
        require(width > 0 && height > 0) { "Swap chain size must be positive" }
        check(_nResize(ptr, width, height)) { "Failed to resize the Dawn swap chain" }
    }

    /** Acquires the current DXGI backbuffer and imports it into Dawn. */
    fun acquireTexture(): WGPUTexture = withPtr { ptr ->
        val texture = _nAcquireTexture(ptr)
        check(texture != NullPointer) { "Failed to acquire a Dawn swap chain texture" }
        WGPUTexture(texture)
    }

    /** Ends Dawn access to the current backbuffer and presents it through DXGI. */
    fun present(vsync: Boolean = true) = withPtr { ptr ->
        _nPresent(ptr, vsync)
    }

    private inline fun <T> withPtr(block: (NativePointer) -> T): T = try {
        check(!isClosed) { "Dawn Direct3D device is closed" }
        Stats.onNativeCall()
        block(pointer)
    } finally {
        reachabilityBarrier(this)
    }

    private object _FinalizerHolder {
        val PTR = _nGetFinalizer()
    }
}

private external fun _nGetFinalizer(): NativePointer

private external fun _nMake(canvas: Canvas): NativePointer

private external fun _nMakeGraphiteContext(devicePtr: NativePointer): NativePointer

private external fun _nResize(devicePtr: NativePointer, width: Int, height: Int): Boolean

private external fun _nAcquireTexture(devicePtr: NativePointer): NativePointer

private external fun _nPresent(devicePtr: NativePointer, vsync: Boolean)
