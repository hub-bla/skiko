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
#include <unordered_set>
#include <mutex>

class FilePersistentCache : public GrContextOptions::PersistentCache {
public:
    FilePersistentCache(const std::string& cacheDir) : fCacheDir(cacheDir) {
        std::filesystem::create_directories(fCacheDir);
        NSLog(@"[ShaderCache] Initialized Persistent Cache at: %s", fCacheDir.c_str());
    }

    sk_sp<SkData> load(const SkData& key) override {
        std::string hashStr = getHashString(key);
        trackUnique(hashStr);

        std::string path = fCacheDir + "/" + hashStr + ".bin";
        std::ifstream file(path, std::ios::binary | std::ios::ate);

        if (!file.is_open()) {
            return nullptr;
        }

        std::streamsize size = file.tellg();
        file.seekg(0, std::ios::beg);
        sk_sp<SkData> data = SkData::MakeUninitialized(size);

        if (file.read(static_cast<char*>(data->writable_data()), size)) {
            NSLog(@"[ShaderCache] HIT  - Loaded %zd bytes", size);
            return data;
        }
        return nullptr;
    }

    void store(const SkData& key, const SkData& data, const SkString& description) override {
        std::string hashStr = getHashString(key);
        trackUnique(hashStr);

        std::string path = fCacheDir + "/" + hashStr + ".bin";
        std::ofstream file(path, std::ios::binary);

        if (file.is_open()) {
            file.write(static_cast<const char*>(data.data()), data.size());
            NSLog(@"[ShaderCache] STORE - Saved %zu bytes", data.size());
        }
    }

private:
    std::string fCacheDir;


    void trackUnique(const std::string& hash) {
        static std::unordered_set<std::string> seenKeys;
        static std::mutex countMutex;

        std::lock_guard<std::mutex> lock(countMutex);
        seenKeys.insert(hash);


        NSLog(@"[ShaderCache] Global Unique Pipelines Seen: %zu", seenKeys.size());
    }

    std::string getHashString(const SkData& key) const {
        unsigned char hash[CC_SHA256_DIGEST_LENGTH];
        CC_SHA256(key.bytes(), static_cast<CC_LONG>(key.size()), hash);

        std::stringstream hexStream;
        hexStream << std::hex << std::setfill('0');
        for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; ++i) {
            hexStream << std::setw(2) << static_cast<int>(hash[i]);
        }
        return hexStream.str();
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

//        auto gShaderCache = std::make_unique<FilePersistentCache>("/tmp/ganesh_shader_cache");

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
