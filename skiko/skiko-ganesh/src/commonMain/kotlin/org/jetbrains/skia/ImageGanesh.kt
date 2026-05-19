package org.jetbrains.skia

import org.jetbrains.skia.impl.*

fun Image.Companion.adoptTextureFrom(
    context: DirectContext,
    backendTexture: BackendTexture,
    origin: SurfaceOrigin,
    colorType: ColorType,
): Image {
    return try {
        Stats.onNativeCall()
        val ptr = _nAdoptTextureFrom(
            getPtr(context),
            getPtr(backendTexture),
            origin.ordinal,
            colorType.ordinal
        )
        if (ptr == Native.NullPointer) throw RuntimeException("Failed to Image::makeFromTexture")
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

fun Image.readPixels(context: DirectContext?, dst: Bitmap, srcX: Int, srcY: Int, cache: Boolean): Boolean =
    readPixelsWithContextPtr(getPtr(context), dst, srcX, srcY, cache)
