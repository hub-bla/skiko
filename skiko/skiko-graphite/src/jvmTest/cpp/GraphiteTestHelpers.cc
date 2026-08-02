#include <jni.h>

#if defined(SK_DIRECT3D)
#include "DawnNativeInterop.hh"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/graphite/dawn/DawnBackendContext.h"
#include "webgpu/webgpu_cpp.h"

#include <cstdint>
#include <utility>
#endif

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_jetbrains_skia_gpu_graphite_GraphiteTestHelpersKt__1nCreateDawnTestObjects(
        JNIEnv* env, jclass) {
#if defined(SK_DIRECT3D)
    static const bool dawnProcsInitialized = [] {
        dawnProcSetProcs(&dawn::native::GetProcs());
        return true;
    }();
    (void)dawnProcsInitialized;

    wgpu::InstanceFeatureName instanceFeatures[] = {
            wgpu::InstanceFeatureName::TimedWaitAny,
    };
    wgpu::InstanceDescriptor instanceDescriptor{};
    instanceDescriptor.requiredFeatureCount = 1;
    instanceDescriptor.requiredFeatures = instanceFeatures;
    wgpu::Instance instance = wgpu::CreateInstance(&instanceDescriptor);
    if (!instance) {
        return env->NewLongArray(0);
    }

    wgpu::Adapter adapter;
    wgpu::RequestAdapterOptions adapterOptions{};
    adapterOptions.backendType = wgpu::BackendType::D3D12;
    adapterOptions.featureLevel = wgpu::FeatureLevel::Core;
    wgpu::Future adapterFuture = instance.RequestAdapter(
            &adapterOptions,
            wgpu::CallbackMode::WaitAnyOnly,
            [&adapter](wgpu::RequestAdapterStatus status,
                       wgpu::Adapter requestedAdapter,
                       wgpu::StringView) {
                if (status == wgpu::RequestAdapterStatus::Success) {
                    adapter = std::move(requestedAdapter);
                }
            });
    if (instance.WaitAny(adapterFuture, UINT64_MAX) != wgpu::WaitStatus::Success || !adapter) {
        return env->NewLongArray(0);
    }

    wgpu::Device device = adapter.CreateDevice();
    if (!device) {
        return env->NewLongArray(0);
    }

    wgpu::TextureDescriptor descriptor{};
    descriptor.size = {8, 8, 1};
    descriptor.format = wgpu::TextureFormat::BGRA8Unorm;
    descriptor.usage = wgpu::TextureUsage::RenderAttachment;
    wgpu::Texture texture = device.CreateTexture(&descriptor);
    if (!texture) {
        return env->NewLongArray(0);
    }

    skgpu::graphite::DawnBackendContext backendContext{};
    backendContext.fInstance = std::move(instance);
    backendContext.fDevice = std::move(device);
    backendContext.fQueue = backendContext.fDevice.GetQueue();
    skgpu::graphite::ContextOptions options{};
    options.fRequireOrderedRecordings = true;
    auto context = skgpu::graphite::ContextFactory::MakeDawn(backendContext, options);
    if (!context) {
        return env->NewLongArray(0);
    }

    jlong objects[] = {
            reinterpret_cast<jlong>(context.release()),
            reinterpret_cast<jlong>(texture.MoveToCHandle()),
    };
    jlongArray result = env->NewLongArray(2);
    env->SetLongArrayRegion(result, 0, 2, objects);
    return result;
#else
    return env->NewLongArray(0);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_org_jetbrains_skia_gpu_graphite_GraphiteTestHelpersKt__1nReleaseDawnTexture(
        JNIEnv*, jclass, jlong texturePtr) {
#if defined(SK_DIRECT3D)
    wgpuTextureRelease(reinterpret_cast<WGPUTexture>(static_cast<uintptr_t>(texturePtr)));
#endif
}
