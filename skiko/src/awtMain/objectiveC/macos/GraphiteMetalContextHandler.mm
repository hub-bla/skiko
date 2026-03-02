#ifdef SK_GRAPHITE
#import <jawt.h>
#import <jawt_md.h>

#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>

#include "core/SkColorSpace.h"
#include "core/SkImageInfo.h"
#include "core/SkSurface.h"
#include "core/SkSurfaceProps.h"

#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/TextureInfo.h"
#include "gpu/graphite/Recorder.h"
#include "gpu/graphite/Surface.h"

#include "include/gpu/graphite/mtl/MtlBackendContext.h"
#include "include/gpu/graphite/mtl/MtlGraphiteUtils.h"
#include "gpu/graphite/mtl/MtlGraphiteTypes.h"

#import "MetalDevice.h"

extern "C"
{
JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_context_GraphiteMetalContextHandler_makeMetalContext(
    JNIEnv* env, jobject contextHandler, jlong devicePtr)
{
    @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void *) devicePtr;
        skgpu::graphite::MtlBackendContext backendContext = {};
        backendContext.fDevice.retain((__bridge CFTypeRef) device.adapter);
        backendContext.fQueue.retain((__bridge CFTypeRef) device.queue);
        skgpu::graphite::ContextOptions options;

        return (jlong) skgpu::graphite::ContextFactory::MakeMetal(backendContext, options).release();
    }
}

JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_context_GraphiteMetalContextHandler_createBackendTexture(
        JNIEnv* env, jobject contextHandler, jlong devicePtr, jint width, jint height)
{
    @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void *) devicePtr;

        /// If we have more than `maximumDrawableCount` command buffers inflight, wait until one of them finishes work.
        dispatch_semaphore_wait(device.inflightSemaphore, DISPATCH_TIME_FOREVER);
        id<CAMetalDrawable> currentDrawable = [device.layer nextDrawable];
        if (!currentDrawable) {
            /// Signal semaphore immediately, no command buffer will be commited
            dispatch_semaphore_signal(device.inflightSemaphore);

            return NULL;
        }
        device.drawableHandle = currentDrawable;

        skgpu::graphite::BackendTexture backendTexture = skgpu::graphite::BackendTextures::MakeMetal(
                SkISize::Make(width, height),
                (__bridge CFTypeRef) currentDrawable.texture
        );

        return reinterpret_cast<jlong>(new skgpu::graphite::BackendTexture(backendTexture));
    }
}

JNIEXPORT void JNICALL Java_org_jetbrains_skiko_context_GraphiteMetalContextHandler_finishFrame(
    JNIEnv *env, jobject contextHandler, jlong devicePtr)
{
  @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void *) devicePtr;
        id<CAMetalDrawable> currentDrawable = device.drawableHandle;

        if (currentDrawable) {
            id<MTLCommandBuffer> commandBuffer = [device.queue commandBuffer];
            commandBuffer.label = @"Present";

            // 1. THE FIX: Tell Metal to present this drawable when the queue finishes
            [commandBuffer presentDrawable:currentDrawable];

            // 2. Signal the semaphore so the NEXT frame can start
            [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
                if (buffer.status == MTLCommandBufferStatusError) {
                    NSLog(@"Presentation Error: %@", buffer.error);
                }
                dispatch_semaphore_signal(device.inflightSemaphore);
            }];

            // 3. Commit to the GPU
            [commandBuffer commit];
            device.drawableHandle = nil;
        }
    }
}
JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_context_GraphiteMetalContextHandler_getTexInfo(
    JNIEnv *env, jobject contextHandler)
{
    @autoreleasepool {
        skgpu::graphite::MtlTextureInfo mtlTexInfo;

        mtlTexInfo.fSampleCount = 1;
        mtlTexInfo.fMipmapped = skgpu::Mipmapped::kNo;
        mtlTexInfo.fFormat = MTLPixelFormatBGRA8Unorm;
        mtlTexInfo.fUsage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        mtlTexInfo.fStorageMode = MTLStorageModeShared;
        mtlTexInfo.fFramebufferOnly = false;

        skgpu::graphite::TextureInfo* textureInfoPtr = new skgpu::graphite::TextureInfo(
            skgpu::graphite::TextureInfos::MakeMetal(mtlTexInfo)
        );

        return reinterpret_cast<jlong>(textureInfoPtr);
    }
}
} // extern C
#endif