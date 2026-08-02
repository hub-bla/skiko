#ifdef SK_DIRECT3D
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <jawt.h>
#include <jawt_md.h>
#include <wrl/client.h>

#include "DawnNativeInterop.hh"
#include "dawn/native/D3D12Backend.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/graphite/dawn/DawnBackendContext.h"

#include <cstdint>
#include <memory>
#include <utility>

namespace {
constexpr UINT kBufferCount = 2;

HWND canvasHwnd(JNIEnv* env, jobject canvas) {
    JAWT awt{};
    awt.version = JAWT_VERSION_1_4;
    if (!JAWT_GetAWT(env, &awt)) return nullptr;
    JAWT_DrawingSurface* surface = awt.GetDrawingSurface(env, canvas);
    if (!surface) return nullptr;
    HWND hwnd = nullptr;
    if (!(surface->Lock(surface) & JAWT_LOCK_ERROR)) {
        auto* info = static_cast<JAWT_Win32DrawingSurfaceInfo*>(surface->GetDrawingSurfaceInfo(surface));
        if (info) {
            hwnd = info->hwnd;
            surface->FreeDrawingSurfaceInfo(info);
        }
        surface->Unlock(surface);
    }
    awt.FreeDrawingSurface(surface);
    return hwnd;
}

class DawnDirectXDevice {
public:
    explicit DawnDirectXDevice(HWND hwnd) : fHwnd(hwnd) {}

    bool initialize() {
        static const bool dawnProcsInitialized = [] {
            dawnProcSetProcs(&dawn::native::GetProcs());
            return true;
        }();
        (void)dawnProcsInitialized;

        wgpu::InstanceFeatureName features[] = {wgpu::InstanceFeatureName::TimedWaitAny};
        wgpu::InstanceDescriptor instanceDescriptor{};
        instanceDescriptor.requiredFeatureCount = 1;
        instanceDescriptor.requiredFeatures = features;
        fInstance = wgpu::CreateInstance(&instanceDescriptor);
        if (!fInstance) return false;

        wgpu::RequestAdapterOptions options{};
        options.backendType = wgpu::BackendType::D3D12;
        options.featureLevel = wgpu::FeatureLevel::Core;
        auto future = fInstance.RequestAdapter(&options, wgpu::CallbackMode::WaitAnyOnly,
                [this](wgpu::RequestAdapterStatus status, wgpu::Adapter adapter, wgpu::StringView) {
                    if (status == wgpu::RequestAdapterStatus::Success) fAdapter = std::move(adapter);
                });
        if (fInstance.WaitAny(future, UINT64_MAX) != wgpu::WaitStatus::Success || !fAdapter ||
                !fAdapter.HasFeature(wgpu::FeatureName::SharedTextureMemoryD3D12Resource)) {
            return false;
        }

        wgpu::Limits limits{};
        if (!fAdapter.GetLimits(&limits)) return false;
        wgpu::FeatureName deviceFeatures[] = {wgpu::FeatureName::SharedTextureMemoryD3D12Resource};
        wgpu::DeviceDescriptor descriptor{};
        descriptor.requiredFeatureCount = 1;
        descriptor.requiredFeatures = deviceFeatures;
        descriptor.requiredLimits = &limits;
        fDevice = fAdapter.CreateDevice(&descriptor);
        if (!fDevice) return false;
        fQueue = fDevice.GetQueue();

        fD3DDevice = dawn::native::d3d12::GetD3D12Device(fDevice.Get());
        fQueue12 = dawn::native::d3d12::GetD3D12CommandQueue(fDevice.Get());
        return fD3DDevice && fQueue12;
    }

    bool resize(UINT width, UINT height) {
        if (fCurrentTexture || width == 0 || height == 0) return false;
        if (!fSwapChain) return createSwapChain(width, height);
        if (FAILED(fSwapChain->ResizeBuffers(kBufferCount, width, height,
                DXGI_FORMAT_R8G8B8A8_UNORM, 0))) return false;
        fWidth = width;
        fHeight = height;
        return true;
    }

    skgpu::graphite::Context* makeGraphiteContext() const {
        skgpu::graphite::DawnBackendContext backend{};
        backend.fInstance = fInstance;
        backend.fDevice = fDevice;
        backend.fQueue = fQueue;
        skgpu::graphite::ContextOptions options{};
        options.fRequireOrderedRecordings = true;
        return skgpu::graphite::ContextFactory::MakeDawn(backend, options).release();
    }

    WGPUTexture acquireTexture() {
        if (!fSwapChain || fCurrentTexture) return nullptr;
        const UINT index = fSwapChain->GetCurrentBackBufferIndex();
        Microsoft::WRL::ComPtr<ID3D12Resource> resource;
        if (FAILED(fSwapChain->GetBuffer(index, IID_PPV_ARGS(&resource)))) return nullptr;

        dawn::native::d3d12::SharedTextureMemoryD3D12ResourceDescriptor resourceDescriptor;
        resourceDescriptor.resource = resource.Get();
        wgpu::SharedTextureMemoryDescriptor descriptor{};
        descriptor.nextInChain = &resourceDescriptor;
        fMemory = fDevice.ImportSharedTextureMemory(&descriptor);
        if (!fMemory) return nullptr;

        wgpu::TextureDescriptor textureDescriptor{};
        textureDescriptor.size = {fWidth, fHeight, 1};
        textureDescriptor.format = wgpu::TextureFormat::RGBA8Unorm;
        textureDescriptor.usage = wgpu::TextureUsage::RenderAttachment;
        fCurrentTexture = fMemory.CreateTexture(&textureDescriptor);
        if (!fCurrentTexture) {
            fMemory = nullptr;
            return nullptr;
        }

        wgpu::SharedTextureMemoryD3DSwapchainBeginState beginState{};
        beginState.isSwapchain = true;
        wgpu::SharedTextureMemoryBeginAccessDescriptor beginDescriptor{};
        beginDescriptor.nextInChain = &beginState;
        if (fMemory.BeginAccess(fCurrentTexture, &beginDescriptor) != wgpu::Status::Success) {
            fCurrentTexture = nullptr;
            fMemory = nullptr;
            return nullptr;
        }
        return fCurrentTexture.Get();
    }

    void present(bool vsync) {
        if (!fSwapChain || !fCurrentTexture) return;
        wgpu::SharedTextureMemoryEndAccessState endState{};
        fMemory.EndAccess(fCurrentTexture, &endState);
        fCurrentTexture = nullptr;
        fMemory = nullptr;
        fSwapChain->Present(vsync ? 1 : 0, 0);
    }

private:
    bool createSwapChain(UINT width, UINT height) {
        Microsoft::WRL::ComPtr<IDXGIFactory4> factory;
        if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return false;
        DXGI_SWAP_CHAIN_DESC1 desc{};
        desc.Width = width;
        desc.Height = height;
        desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        desc.BufferCount = kBufferCount;
        desc.Scaling = DXGI_SCALING_STRETCH;
        desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
        Microsoft::WRL::ComPtr<IDXGISwapChain1> swapChain;
        if (FAILED(factory->CreateSwapChainForHwnd(fQueue12, fHwnd, &desc, nullptr, nullptr, &swapChain)) ||
                FAILED(swapChain.As(&fSwapChain))) return false;
        factory->MakeWindowAssociation(fHwnd, DXGI_MWA_NO_ALT_ENTER);
        fWidth = width;
        fHeight = height;
        return true;
    }

    HWND fHwnd = nullptr;
    wgpu::Instance fInstance;
    wgpu::Adapter fAdapter;
    wgpu::Device fDevice;
    wgpu::Queue fQueue;
    ID3D12Device* fD3DDevice = nullptr;
    ID3D12CommandQueue* fQueue12 = nullptr;
    Microsoft::WRL::ComPtr<IDXGISwapChain3> fSwapChain;
    wgpu::SharedTextureMemory fMemory;
    wgpu::Texture fCurrentTexture;
    UINT fWidth = 0;
    UINT fHeight = 0;
};

void deleteDawnDirectXDevice(DawnDirectXDevice* device) { delete device; }

DawnDirectXDevice* fromPtr(jlong ptr) {
    return reinterpret_cast<DawnDirectXDevice*>(static_cast<uintptr_t>(ptr));
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nGetFinalizer(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(&deleteDawnDirectXDevice);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nMake(JNIEnv* env, jclass, jobject canvas) {
    HWND hwnd = canvasHwnd(env, canvas);
    if (!hwnd) return 0;
    auto device = std::make_unique<DawnDirectXDevice>(hwnd);
    if (!device->initialize()) return 0;
    return reinterpret_cast<jlong>(device.release());
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nMakeGraphiteContext(
        JNIEnv*, jclass, jlong devicePtr) {
    return reinterpret_cast<jlong>(fromPtr(devicePtr)->makeGraphiteContext());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nResize(
        JNIEnv*, jclass, jlong devicePtr, jint width, jint height) {
    return fromPtr(devicePtr)->resize(static_cast<UINT>(width), static_cast<UINT>(height))
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nAcquireTexture(
        JNIEnv*, jclass, jlong devicePtr) {
    return reinterpret_cast<jlong>(fromPtr(devicePtr)->acquireTexture());
}

extern "C" JNIEXPORT void JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nPresent(
        JNIEnv*, jclass, jlong devicePtr, jboolean vsync) {
    fromPtr(devicePtr)->present(vsync);
}
#endif
