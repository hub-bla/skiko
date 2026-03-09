#ifndef SK_GRPAHITE
#ifdef SK_METAL

#import <jawt.h>
#import <jawt_md.h>

#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>
#import "ganesh/GrDirectContext.h"
#import "gpu/ganesh/GrBackendSurface.h"
#import "ganesh/mtl/GrMtlBackendContext.h"
#import "ganesh/mtl/GrMtlDirectContext.h"
#import "ganesh/mtl/GrMtlBackendSurface.h"
#import "ganesh/mtl/GrMtlTypes.h"

#import "MetalDevice.h"

#include "include/gpu/ganesh/GrContextOptions.h"
#include "include/core/SkData.h"
#include "include/core/SkString.h"

#include <fstream>
#include <string>
#include <filesystem>
#include <iomanip>
#include <sstream>
#import <Foundation/Foundation.h>
#import <CommonCrypto/CommonDigest.h>
class FilePersistentCache : public GrContextOptions::PersistentCache {
public:
    // Initialize with a path to your desired cache directory
    FilePersistentCache(const std::string& cacheDir) : fCacheDir(cacheDir) {
        std::filesystem::create_directories(fCacheDir);
        NSLog(@"[ShaderCache] Initialized Persistent Cache at directory: %s", fCacheDir.c_str());
    }

    // Called by Ganesh to see if we already have the compiled shader
    sk_sp<SkData> load(const SkData& key) override {
        std::string path = getFilePath(key);
        std::ifstream file(path, std::ios::binary | std::ios::ate);

        if (!file.is_open()) {
            NSLog(@"[ShaderCache] MISS - Shader not found: %s", path.c_str());
            return nullptr;
        }

        std::streamsize size = file.tellg();
        file.seekg(0, std::ios::beg);

        sk_sp<SkData> data = SkData::MakeUninitialized(size);
        if (file.read(static_cast<char*>(data->writable_data()), size)) {
                NSLog(@"[ShaderCache] HIT  - Loaded %zd bytes from: %s", size, path.c_str());
            return data;
        }

        NSLog(@"[ShaderCache] ERROR - Failed to read file: %s", path.c_str());
        return nullptr;
    }

    // Called by Ganesh when a new shader is compiled. We dump it to disk here.
    void store(const SkData& key, const SkData& data, const SkString& description) override {
        std::string path = getFilePath(key);
        std::ofstream file(path, std::ios::binary);

        if (file.is_open()) {
            file.write(static_cast<const char*>(data.data()), data.size());
            // SkString has a .c_str() method, allowing us to print Ganesh's internal description
            NSLog(@"[ShaderCache] STORE - Saved %zu bytes to: %s",
                  data.size(), path.c_str());
        } else {
            NSLog(@"[ShaderCache] ERROR - Failed to open file for writing: %s", path.c_str());
        }
    }

private:
    std::string fCacheDir;

    std::string getFilePath(const SkData& key) const {
        unsigned char hash[CC_SHA256_DIGEST_LENGTH];
        CC_SHA256(key.bytes(), static_cast<CC_LONG>(key.size()), hash);

        std::stringstream hexStream;
        hexStream << std::hex << std::setfill('0');
        for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; ++i) {
            hexStream << std::setw(2) << static_cast<int>(hash[i]);
        }

        return fCacheDir + "/" + hexStream.str() + ".bin";
    }
};



extern "C"
{
JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_context_MetalContextHandler_makeMetalContext(
    JNIEnv* env, jobject contextHandler, jlong devicePtr)
{
    @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void*) devicePtr;
        GrMtlBackendContext backendContext = {};
        backendContext.fDevice.retain((__bridge GrMTLHandle) device.adapter);
        backendContext.fQueue.retain((__bridge GrMTLHandle) device.queue);

        GrContextOptions options;

        auto gShaderCache = std::make_unique<FilePersistentCache>("/Users/hubert.blaszczyk/Desktop/ganesh_shader_cache");

//        options.fPersistentCache = gShaderCache.release();


        return (jlong) GrDirectContexts::MakeMetal(backendContext, options).release();
    }
}

JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_context_MetalContextHandler_makeMetalRenderTarget(
    JNIEnv* env, jobject contextHandler, jlong devicePtr, jint width, jint height)
{
    @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void *) devicePtr;
        GrBackendRenderTarget* renderTarget = NULL;

        /// If we have more than `maximumDrawableCount` command buffers inflight, wait until one of them finishes work.
        dispatch_semaphore_wait(device.inflightSemaphore, DISPATCH_TIME_FOREVER);

        id<CAMetalDrawable> currentDrawable = [device.layer nextDrawable];
        if (!currentDrawable) {
            /// Signal semaphore immediately, no command buffer will be commited
            dispatch_semaphore_signal(device.inflightSemaphore);

            return NULL;
        }
        device.drawableHandle = currentDrawable;
        GrMtlTextureInfo info;
        info.fTexture.retain((__bridge GrMTLHandle) currentDrawable.texture);
        GrBackendRenderTarget obj = GrBackendRenderTargets::MakeMtl(width, height, info);
        renderTarget = new GrBackendRenderTarget(obj);
        return (jlong) renderTarget;
    }
}

JNIEXPORT void JNICALL Java_org_jetbrains_skiko_context_MetalContextHandler_finishFrame(
    JNIEnv *env, jobject contextHandler, jlong devicePtr)
{
    @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void *) devicePtr;

        id<CAMetalDrawable> currentDrawable = device.drawableHandle;

        if (currentDrawable) {
            id<MTLCommandBuffer> commandBuffer = [device.queue commandBuffer];
            commandBuffer.label = @"Present";

            [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
                /// commands have completed, allow next waiting (if any) to start encoding new work to gpu
                dispatch_semaphore_signal(device.inflightSemaphore);
            }];

            [commandBuffer addScheduledHandler:^(id<MTLCommandBuffer> buffer) {
                int drawableWidth = currentDrawable.texture.width;
                int drawableHeight = currentDrawable.texture.height;

                int layerWidth = device.layer.drawableSize.width;
                int layerHeight = device.layer.drawableSize.height;

                /// Avoid presenting drawable on layer that has already changed size by the moment it was scheduled
                if (drawableWidth == layerWidth && drawableHeight == layerHeight) {
                    [currentDrawable present];
                }
            }];

            [commandBuffer commit];
            device.drawableHandle = nil;
        }
    }
}

} // extern C
#endif
#endif
