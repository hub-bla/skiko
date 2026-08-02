package org.jetbrains.skia.gpu.graphite

import org.jetbrains.skia.impl.Native.Companion.NullPointer
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.ExperimentalSkikoApi

/**
 * A non-owning handle to a Dawn WebGPU texture.
 *
 * The texture producer must keep the native texture alive while this handle is used.
 */
@ExperimentalSkikoApi
@JvmInline
value class WGPUTexture(internal val nativePtr: NativePointer) {
    init {
        require(nativePtr != NullPointer) { "WGPU texture pointer is null" }
    }
}
