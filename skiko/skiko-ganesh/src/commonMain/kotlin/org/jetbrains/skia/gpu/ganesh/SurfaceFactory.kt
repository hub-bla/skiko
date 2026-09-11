package org.jetbrains.skia.gpu.ganesh

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.impl.Native.Companion.NullPointer
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.Stats
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skia.impl.reachabilityBarrier

fun Surface.Companion.makeFromBackendRenderTarget(
    context: DirectContext,
    rt: BackendRenderTarget,
    origin: SurfaceOrigin,
    colorFormat: SurfaceColorFormat,
    colorSpace: ColorSpace?,
    surfaceProps: SurfaceProps? = null,
): Surface? {
    return try {
        Stats.onNativeCall()
        val ptr = interopScope {
            _nMakeFromBackendRenderTarget(
                getPtr(context),
                getPtr(rt),
                origin.ordinal,
                colorFormat.ordinal,
                getPtr(colorSpace),
                toInterop(surfaceProps?.packToIntArray()),
            )
        }
        if (ptr == NullPointer) null else Surface(ptr, context, rt)
    } finally {
        reachabilityBarrier(context)
        reachabilityBarrier(rt)
        reachabilityBarrier(colorSpace)
    }
}

fun Surface.Companion.makeFromMTKView(
    context: DirectContext,
    mtkViewPtr: NativePointer,
    origin: SurfaceOrigin,
    sampleCount: Int,
    colorFormat: SurfaceColorFormat,
    colorSpace: ColorSpace?,
    surfaceProps: SurfaceProps?,
): Surface {
    return try {
        Stats.onNativeCall()
        val ptr = interopScope {
            _nMakeFromMTKView(
                getPtr(context),
                mtkViewPtr,
                origin.ordinal,
                sampleCount,
                colorFormat.ordinal,
                getPtr(colorSpace),
                toInterop(surfaceProps?.packToIntArray()),
            )
        }
        require(ptr != NullPointer) {
            "Failed Surface.makeFromMTKView($context, $mtkViewPtr $origin, $colorFormat, $surfaceProps)"
        }
        Surface(ptr, context)
    } finally {
        reachabilityBarrier(context)
        reachabilityBarrier(colorSpace)
    }
}

fun Surface.Companion.makeRenderTarget(
    context: DirectContext,
    budgeted: Boolean,
    imageInfo: ImageInfo,
): Surface = makeRenderTarget(context, budgeted, imageInfo, 0, SurfaceOrigin.BOTTOM_LEFT, null, false)

fun Surface.Companion.makeRenderTarget(
    context: DirectContext,
    budgeted: Boolean,
    imageInfo: ImageInfo,
    sampleCount: Int,
    surfaceProps: SurfaceProps?,
): Surface = makeRenderTarget(
    context,
    budgeted,
    imageInfo,
    sampleCount,
    SurfaceOrigin.BOTTOM_LEFT,
    surfaceProps,
    false,
)

fun Surface.Companion.makeRenderTarget(
    context: DirectContext,
    budgeted: Boolean,
    imageInfo: ImageInfo,
    sampleCount: Int,
    origin: SurfaceOrigin,
    surfaceProps: SurfaceProps?,
): Surface = makeRenderTarget(context, budgeted, imageInfo, sampleCount, origin, surfaceProps, false)

fun Surface.Companion.makeRenderTarget(
    context: DirectContext,
    budgeted: Boolean,
    imageInfo: ImageInfo,
    sampleCount: Int,
    origin: SurfaceOrigin,
    surfaceProps: SurfaceProps?,
    shouldCreateWithMips: Boolean,
): Surface {
    return try {
        Stats.onNativeCall()
        val ptr = interopScope {
            _nMakeRenderTarget(
                getPtr(context),
                budgeted,
                imageInfo.width,
                imageInfo.height,
                imageInfo.colorInfo.colorType.ordinal,
                imageInfo.colorInfo.alphaType.ordinal,
                getPtr(imageInfo.colorInfo.colorSpace),
                sampleCount,
                origin.ordinal,
                toInterop(surfaceProps?.packToIntArray()),
                shouldCreateWithMips,
            )
        }
        require(ptr != NullPointer) {
            "Failed Surface.makeRenderTarget($context, $budgeted, $imageInfo, $sampleCount, $origin, $surfaceProps, $shouldCreateWithMips)"
        }
        Surface(ptr, context)
    } finally {
        reachabilityBarrier(context)
        reachabilityBarrier(imageInfo.colorInfo.colorSpace)
    }
}

@ExternalSymbolName("org_jetbrains_skia_gpu_ganesh_SurfaceFactory__1nMakeFromBackendRenderTarget")
private external fun _nMakeFromBackendRenderTarget(
    contextPtr: NativePointer,
    backendRenderTargetPtr: NativePointer,
    surfaceOrigin: Int,
    colorType: Int,
    colorSpacePtr: NativePointer,
    surfaceProps: InteropPointer,
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_ganesh_SurfaceFactory__1nMakeFromMTKView")
private external fun _nMakeFromMTKView(
    contextPtr: NativePointer,
    mtkViewPtr: NativePointer,
    surfaceOrigin: Int,
    sampleCount: Int,
    colorType: Int,
    colorSpacePtr: NativePointer,
    surfaceProps: InteropPointer,
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_ganesh_SurfaceFactory__1nMakeRenderTarget")
private external fun _nMakeRenderTarget(
    contextPtr: NativePointer,
    budgeted: Boolean,
    width: Int,
    height: Int,
    colorType: Int,
    alphaType: Int,
    colorSpacePtr: NativePointer,
    sampleCount: Int,
    surfaceOrigin: Int,
    surfaceProps: InteropPointer,
    shouldCreateWithMips: Boolean,
): NativePointer