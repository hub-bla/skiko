package org.jetbrains.skiko

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.gpu.ganesh.GaneshLibrary

internal fun makeGLContext(): DirectContext {
    GaneshLibrary.load()
    return DirectContext(makeGLContextNative())
}

internal fun makeGLRenderTarget(width: Int, height: Int, sampleCnt: Int, stencilBits: Int, fbId: Int, fbFormat: Int): BackendRenderTarget {
    GaneshLibrary.load()
    return BackendRenderTarget(makeGLRenderTargetNative(width, height, sampleCnt, stencilBits, fbId, fbFormat))
}

internal fun makeMetalRenderTarget(width: Int, height: Int, sampleCnt: Int): BackendRenderTarget {
    GaneshLibrary.load()
    return BackendRenderTarget(makeMetalRenderTargetNative(width, height, sampleCnt).also { if (it == 0L) TODO("not yet supported") })
}

internal fun makeMetalContext(): DirectContext {
    GaneshLibrary.load()
    return DirectContext(makeMetalContextNative().also { if (it == 0L) TODO("not yet supported") })
}

private external fun makeGLRenderTargetNative(width: Int, height: Int, sampleCnt: Int, stencilBits: Int, fbId: Int, fbFormat: Int): Long
private external fun makeGLContextNative(): Long

private external fun makeMetalRenderTargetNative(width: Int, height: Int, sampleCnt: Int): Long
private external fun makeMetalContextNative(): Long
