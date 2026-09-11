package org.jetbrains.skia.gpu.ganesh

import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.impl.Native.Companion.NullPointer
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.Stats
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skia.impl.reachabilityBarrier

fun Image.Companion.adoptTextureFrom(
    context: DirectContext,
    backendTexture: BackendTexture,
    origin: SurfaceOrigin,
    colorType: ColorType,
): Image = adoptTextureFrom(context, backendTexture, origin, colorType, null)

fun Image.Companion.adoptTextureFrom(
    context: DirectContext,
    backendTexture: BackendTexture,
    origin: SurfaceOrigin,
    colorType: ColorType,
    alphaType: ColorAlphaType?,
): Image {
    return try {
        Stats.onNativeCall()
        val ptr = if (alphaType == null) {
            _nAdoptTextureFrom(getPtr(context), getPtr(backendTexture), origin.ordinal, colorType.ordinal)
        } else {
            _nAdoptTextureFromAlphaType(
                getPtr(context),
                getPtr(backendTexture),
                origin.ordinal,
                colorType.ordinal,
                alphaType.ordinal,
            )
        }
        require(ptr != NullPointer) { "Failed to Image::makeFromTexture" }
        Image(ptr)
    } finally {
        reachabilityBarrier(context)
        reachabilityBarrier(backendTexture)
    }
}

fun Image.readPixels(context: DirectContext, dst: Bitmap): Boolean =
    readPixels(context, dst, 0, 0, false)

fun Image.readPixels(context: DirectContext, dst: Bitmap, srcX: Int, srcY: Int): Boolean =
    readPixels(context, dst, srcX, srcY, false)

/**
 * Reads pixels using the supplied Ganesh context.
 *
 * This has a separate native bridge from core's context-free [Image.readPixels]. A real
 * [DirectContext] must be passed to Skia for texture-backed images; delegating to the context-free
 * pixmap overload would not be equivalent and may fail to read GPU-backed pixels.
 */
fun Image.readPixels(
    context: DirectContext,
    dst: Bitmap,
    srcX: Int,
    srcY: Int,
    cache: Boolean,
): Boolean {
    return try {
        _nReadPixelsBitmapWithContext(
            getPtr(this),
            getPtr(context),
            getPtr(dst),
            srcX,
            srcY,
            cache,
        )
    } finally {
        reachabilityBarrier(this)
        reachabilityBarrier(context)
        reachabilityBarrier(dst)
    }
}

@ExternalSymbolName("org_jetbrains_skia_gpu_ganesh_ImageFactory__1nReadPixelsBitmapWithContext")
private external fun _nReadPixelsBitmapWithContext(
    imagePtr: NativePointer,
    contextPtr: NativePointer,
    bitmapPtr: NativePointer,
    srcX: Int,
    srcY: Int,
    cache: Boolean,
): Boolean

@ExternalSymbolName("org_jetbrains_skia_gpu_ganesh_ImageFactory__1nAdoptTextureFrom")
private external fun _nAdoptTextureFrom(
    contextPtr: NativePointer,
    backendTexturePtr: NativePointer,
    surfaceOrigin: Int,
    colorType: Int,
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_ganesh_ImageFactory__1nAdoptTextureFromAlphaType")
private external fun _nAdoptTextureFromAlphaType(
    contextPtr: NativePointer,
    backendTexturePtr: NativePointer,
    surfaceOrigin: Int,
    colorType: Int,
    alphaType: Int,
): NativePointer