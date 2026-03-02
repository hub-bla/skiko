#include "ganesh/GrDirectContext.h"
#include "ganesh/gl/GrGLInterface.h"
#include "common.h"
#include "ganesh/gl/GrGLDirectContext.h" // TODO: skia update: check if it's correct

#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "gpu/graphite/Recorder.h"

#ifdef SK_METAL
#include "ganesh/mtl/GrMtlBackendContext.h"
#include "ganesh/mtl/GrMtlDirectContext.h"
#include "gpu/graphite/mtl/MtlGraphiteTypes_cpp.h"
#include "include/gpu/graphite/mtl/MtlBackendContext.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/precompile/Precompile.h"
#include "include/gpu/graphite/precompile/PrecompileShader.h"
#include "include/gpu/graphite/PrecompileContext.h"
#include "include/gpu/graphite/precompile/PaintOptions.h"
#include "include/core/SkExecutor.h"

SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nGraphiteMakeMetal
        (KNativePointer devicePtr, KNativePointer queuePtr) {
    skgpu::graphite::MtlBackendContext backendContext = {};
    backendContext.fDevice.retain(reinterpret_cast<CFTypeRef>(devicePtr));
    backendContext.fQueue.retain(reinterpret_cast<CFTypeRef>(queuePtr));
    skgpu::graphite::ContextOptions options;
    options.fRequireOrderedRecordings = true;
    // fClientWillExternallySynchronizeAllThreads
    // fRequireOrderedRecordings
    return static_cast<KNativePointer>(skgpu::graphite::ContextFactory::MakeMetal(backendContext, options).release());
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nPrecompile(KNativePointer contextPtr) {
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    auto precompileContext = context->makePrecompileContext();

    using DTF = skgpu::graphite::DrawTypeFlags;

    // Define the common "Canvas"
    skgpu::graphite::RenderPassProperties props;
    props.fDstCT = kBGRA_8888_SkColorType;
    props.fRequiresMSAA = false;

    // Define specific use cases to avoid cross-product bloat
    struct PrecompileCase {
        skgpu::graphite::PaintOptions options;
        DTF flags;
    };

    std::vector<PrecompileCase> cases;

    // Case 1: Standard UI (Buttons, Rects, Backgrounds)
    {
        skgpu::graphite::PaintOptions opt;
        opt.setShaders({skgpu::graphite::PrecompileShaders::Color()});
        opt.setBlendModes({SkBlendMode::kSrcOver, SkBlendMode::kSrc});
        cases.push_back({opt, static_cast<DTF>(DTF::kSimpleShape | DTF::kAnalyticRRect | DTF::kNonAAFillRect)});
    }

    // Case 2: Text Rendering (The most common task)
    {
        skgpu::graphite::PaintOptions opt;
        opt.setShaders({skgpu::graphite::PrecompileShaders::Color()});
        opt.setBlendModes({SkBlendMode::kSrcOver});
        cases.push_back({opt, static_cast<DTF>(DTF::kBitmapText_Mask | DTF::kBitmapText_Color | DTF::kSDFText)});
    }

    // Case 3: Images & Icons (Compositor layers)
    {
        skgpu::graphite::PaintOptions opt;
        opt.setShaders({skgpu::graphite::PrecompileShaders::Image()});
        opt.setBlendModes({SkBlendMode::kSrcOver, SkBlendMode::kSrc});
        cases.push_back({opt, static_cast<DTF>(DTF::kPerEdgeAAQuad | DTF::kSimpleShape)});
    }

    // Case 4: Gradients (Modern UI Headers)
    {
        skgpu::graphite::PaintOptions opt;
        opt.setShaders({skgpu::graphite::PrecompileShaders::LinearGradient(
            skgpu::graphite::PrecompileShaders::GradientShaderFlags::kSmall)});
        opt.setBlendModes({SkBlendMode::kSrcOver});
        cases.push_back({opt, static_cast<DTF>(DTF::kAnalyticRRect | DTF::kNonAAFillRect)});
    }

    skgpu::graphite::RenderPassProperties msaaProps = props;
    msaaProps.fRequiresMSAA = true;

    for (const auto& c : cases) {
        // Warm up the standard version
        skgpu::graphite::Precompile(precompileContext.get(), c.options, c.flags, { &props, 1 });

        // Warm up MSAA for UI shapes specifically (Text usually handles its own AA)
        if (c.flags & (DTF::kSimpleShape | DTF::kAnalyticRRect)) {
            skgpu::graphite::Precompile(precompileContext.get(), c.options, c.flags, { &msaaProps, 1 });
        }
    }
}
#endif

#ifdef SK_DIRECT3D
#include "ganesh/d3d/GrD3DBackendContext.h"
#endif


SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nGraphiteSubmit
(KNativePointer contextPtr, KNativePointer recorderPtr) {
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    skgpu::graphite::Recorder *graphiteRecorder = reinterpret_cast<skgpu::graphite::Recorder*>(recorderPtr);

    std::unique_ptr<skgpu::graphite::Recording> recording = graphiteRecorder->snap();

    skgpu::graphite::InsertRecordingInfo info;
    info.fRecording = recording.get();

    if (!context->insertRecording(info)) {
        printf("Context::insertRecording failed\n");
        return;
    }

    context->submit(skgpu::graphite::SyncToCpu::kNo);
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nInsertRecording
        (KNativePointer contextPtr, KNativePointer recorderPtr) {
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    skgpu::graphite::Recorder *graphiteRecorder = reinterpret_cast<skgpu::graphite::Recorder*>(recorderPtr);

    std::unique_ptr<skgpu::graphite::Recording> recording = graphiteRecorder->snap();

    skgpu::graphite::InsertRecordingInfo info;
    info.fRecording = recording.get();

    context->insertRecording(info);
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nDefaultGraphiteSubmit
        (KNativePointer contextPtr) {
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    context->submit(skgpu::graphite::SyncToCpu::kYes);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nMakeGL
  () {
    return static_cast<KNativePointer>(GrDirectContexts::MakeGL().release());
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nMakeGLWithInterface
  (KNativePointer ptr) {
    sk_sp<GrGLInterface> iface = sk_ref_sp(reinterpret_cast<GrGLInterface*>(ptr));
    return static_cast<KNativePointer>(GrDirectContexts::MakeGL(iface).release());
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nMakeMetal
  (KNativePointer devicePtr, KNativePointer queuePtr) {
    return static_cast<KNativePointer>(nullptr);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nMakeDirect3D
  (KNativePointer adapterPtr, KNativePointer devicePtr, KNativePointer queuePtr) {
#ifdef SK_DIRECT3D
    GrD3DBackendContext backendContext = {};
    IDXGIAdapter1* adapter = reinterpret_cast<IDXGIAdapter1*>(adapterPtr);
    ID3D12Device* device = reinterpret_cast<ID3D12Device*>(devicePtr);
    ID3D12CommandQueue* queue = reinterpret_cast<ID3D12CommandQueue*>(queuePtr);
    backendContext.fAdapter.retain(adapter);
    backendContext.fDevice.retain(device);
    backendContext.fQueue.retain(queue);
    sk_sp<GrDirectContext> instance = GrDirectContext::MakeDirect3D(backendContext);
    return static_cast<KNativePointer>(instance.release());
#else // SK_DIRECT3D
    return nullptr;
#endif // SK_DIRECT3D
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nFlushDefault
  (KNativePointer ptr) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>((ptr));
    context->flush(GrFlushInfo());
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nFlush
  (KNativePointer ptr, KNativePointer skSurfacePtr) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(ptr);
    SkSurface* skSurface = reinterpret_cast<SkSurface*>(skSurfacePtr);
    context->flush(skSurface);
}

SKIKO_EXPORT KLong org_jetbrains_skia_DirectContext__1nGetResourceCacheLimit
  (KNativePointer ptr) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(ptr);
    return (KLong) context->getResourceCacheLimit();
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nSetResourceCacheLimit
  (KNativePointer ptr, KLong maxResourceBytes) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(ptr);
    context->setResourceCacheLimit((size_t) maxResourceBytes);
}

GrSyncCpu grSyncCpuFromBool(bool syncCpu) {
    if (syncCpu) return GrSyncCpu::kYes;
    return GrSyncCpu::kNo;
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nFlushAndSubmit
  (KNativePointer ptr, KNativePointer skSurfacePtr, KBoolean syncCpu) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(ptr);
    SkSurface* skSurface = reinterpret_cast<SkSurface*>(skSurfacePtr);
    context->flushAndSubmit(skSurface, grSyncCpuFromBool(syncCpu));
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nSubmit
  (KNativePointer ptr, KBoolean syncCpu) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>((ptr));
    context->submit(grSyncCpuFromBool(syncCpu));
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nReset
  (KNativePointer ptr, KInt flags) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>((ptr));
    context->resetContext((uint32_t) flags);
}

SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nAbandon
  (KNativePointer ptr, KInt flags) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>((ptr));
    context->abandonContext();
}

#include "webgpu/webgpu_cpp.h"
#include "dawn/dawn_proc.h"
#include "dawn/native/DawnNative.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/dawn/DawnBackendContext.h"
#include "dawn/dawn_proc.h"
#include "gpu/graphite/dawn/DawnGraphiteTypes.h"

//struct SkikoDawnContext {
//    wgpu::Device
//    wgpu::Instance
//    wgpu::WGPUTexture
//
//};

SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nDawnBackendContext() {
    DawnProcTable backendProcs = dawn::native::GetProcs();
    dawnProcSetProcs(&backendProcs);
//    wgpu::InstanceLimits limits{.timedWaitAnyMaxCount = 64};
//    instanceDesc.requiredLimits = &limits;
    static const auto kTimedWaitAny = wgpu::InstanceFeatureName::TimedWaitAny;

    wgpu::InstanceDescriptor instanceDesc{.requiredFeatureCount = 1,
            .requiredFeatures = &kTimedWaitAny};

    wgpu::InstanceLimits limits;
    auto instance = std::make_unique<dawn::native::Instance>(&instanceDesc);

    auto useTintIR = false;
    static constexpr const char *kToggles[] = {
#if !defined(SK_DEBUG)
            "skip_validation",
#endif
            "disable_lazy_clear_for_mapped_at_creation_buffer",
            "allow_unsafe_apis",
            "use_user_defined_labels_in_backend",
            "disable_robustness",
            "use_tint_ir",
    };
    wgpu::DawnTogglesDescriptor togglesDesc;
    togglesDesc.enabledToggleCount = std::size(kToggles) - (useTintIR ? 0 : 1);
    togglesDesc.enabledToggles = kToggles;

    dawn::native::Adapter matchedAdaptor;

    wgpu::RequestAdapterOptions options;

    constexpr auto kDefaultBackendType = wgpu::BackendType::Metal;

    options.backendType = kDefaultBackendType;
    options.featureLevel = wgpu::FeatureLevel::Core;
    options.nextInChain = &togglesDesc;

    std::vector<dawn::native::Adapter> adapters =
            instance->EnumerateAdapters(&options);
    if (adapters.empty()) {
        throw std::runtime_error("No matching adapter found");
    }

    // Sort adapters by adapterType(DiscreteGPU, IntegratedGPU, CPU) and
    // backendType(Metal, Vulkan, OpenGL, OpenGLES, WebGPU).
    std::sort(adapters.begin(), adapters.end(),
            [](dawn::native::Adapter a, dawn::native::Adapter b) {
                wgpu::Adapter wgpuA = a.Get();
                wgpu::Adapter wgpuB = b.Get();
                wgpu::AdapterInfo infoA;
                wgpu::AdapterInfo infoB;
                wgpuA.GetInfo(&infoA);
                wgpuB.GetInfo(&infoB);
                return std::tuple(infoA.adapterType, infoA.backendType) <
                        std::tuple(infoB.adapterType, infoB.backendType);
            });

    for (const auto &adapter : adapters) {
        wgpu::Adapter wgpuAdapter = adapter.Get();
        wgpu::AdapterInfo props;
        wgpuAdapter.GetInfo(&props);
        if (kDefaultBackendType == props.backendType) {
            matchedAdaptor = adapter;
            break;
        }
    }

    if (!matchedAdaptor) {
        throw std::runtime_error("No matching adapter found");
    }

    wgpu::Adapter adapter = matchedAdaptor.Get();

    std::vector<wgpu::FeatureName> features;
    if (adapter.HasFeature(wgpu::FeatureName::MSAARenderToSingleSampled)) {
        features.push_back(wgpu::FeatureName::MSAARenderToSingleSampled);
    }
    if (adapter.HasFeature(wgpu::FeatureName::TransientAttachments)) {
        features.push_back(wgpu::FeatureName::TransientAttachments);
    }
    if (adapter.HasFeature(wgpu::FeatureName::Unorm16TextureFormats)) {
        features.push_back(wgpu::FeatureName::Unorm16TextureFormats);
    }
    if (adapter.HasFeature(wgpu::FeatureName::DualSourceBlending)) {
        features.push_back(wgpu::FeatureName::DualSourceBlending);
    }
    if (adapter.HasFeature(wgpu::FeatureName::FramebufferFetch)) {
        features.push_back(wgpu::FeatureName::FramebufferFetch);
    }
    if (adapter.HasFeature(wgpu::FeatureName::BufferMapExtendedUsages)) {
        features.push_back(wgpu::FeatureName::BufferMapExtendedUsages);
    }
    if (adapter.HasFeature(wgpu::FeatureName::TextureCompressionETC2)) {
        features.push_back(wgpu::FeatureName::TextureCompressionETC2);
    }
    if (adapter.HasFeature(wgpu::FeatureName::TextureCompressionBC)) {
        features.push_back(wgpu::FeatureName::TextureCompressionBC);
    }
    if (adapter.HasFeature(wgpu::FeatureName::R8UnormStorage)) {
        features.push_back(wgpu::FeatureName::R8UnormStorage);
    }
    if (adapter.HasFeature(wgpu::FeatureName::DawnLoadResolveTexture)) {
        features.push_back(wgpu::FeatureName::DawnLoadResolveTexture);
    }
    if (adapter.HasFeature(wgpu::FeatureName::DawnPartialLoadResolveTexture)) {
        features.push_back(wgpu::FeatureName::DawnPartialLoadResolveTexture);
    }
    if (adapter.HasFeature(wgpu::FeatureName::TimestampQuery)) {
        features.push_back(wgpu::FeatureName::TimestampQuery);
    }
    if (adapter.HasFeature(wgpu::FeatureName::DawnTexelCopyBufferRowAlignment)) {
        features.push_back(wgpu::FeatureName::DawnTexelCopyBufferRowAlignment);
    }
    if (adapter.HasFeature(wgpu::FeatureName::ImplicitDeviceSynchronization)) {
        features.push_back(wgpu::FeatureName::ImplicitDeviceSynchronization);
    }
    if (adapter.HasFeature(wgpu::FeatureName::SharedTextureMemoryIOSurface)) {
        features.push_back(wgpu::FeatureName::SharedTextureMemoryIOSurface);
    }

    wgpu::DeviceDescriptor desc;
    desc.requiredFeatureCount = features.size();
    desc.requiredFeatures = features.data();
    desc.nextInChain = &togglesDesc;


    wgpu::Device device =
            wgpu::Device::Acquire(matchedAdaptor.CreateDevice(&desc));
    SkASSERT(device);

    auto* backendContext = new skgpu::graphite::DawnBackendContext();
    backendContext->fInstance = wgpu::Instance(instance->Get());
    backendContext->fDevice = device;
    backendContext->fQueue = device.GetQueue();

    return reinterpret_cast<KNativePointer>(backendContext);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nCreateDawnContext(KNativePointer backendContextPtr) {
    skgpu::graphite::DawnBackendContext *backendContext = reinterpret_cast<skgpu::graphite::DawnBackendContext*>(backendContextPtr);
    skgpu::graphite::ContextOptions contextOptions;
    auto context = skgpu::graphite::ContextFactory::MakeDawn(*backendContext, contextOptions);

    return reinterpret_cast<KNativePointer>(context.release());
}


SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nCreateDawnBackendTexture(KNativePointer backendContextPtr, KNativePointer recorderPtr, KInt width, KInt height) {
    skgpu::graphite::DawnBackendContext *backendContext = reinterpret_cast<skgpu::graphite::DawnBackendContext*>(backendContextPtr);
    skgpu::graphite::Recorder *recorder = reinterpret_cast<skgpu::graphite::Recorder*>(recorderPtr);

    auto device = backendContext->fDevice;
    WGPUTextureDescriptor texDesc{};

    texDesc.usage         = WGPUTextureUsage_RenderAttachment |
            WGPUTextureUsage_CopySrc |
            WGPUTextureUsage_TextureBinding;
    texDesc.dimension     = WGPUTextureDimension_2D;
    texDesc.size = {
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            1
    };
    texDesc.format        = WGPUTextureFormat_BGRA8Unorm;
    texDesc.mipLevelCount = 1;
    texDesc.sampleCount   = 1;

    WGPUTexture wgpuTex = wgpuDeviceCreateTexture(device.Get(), &texDesc);
    skgpu::graphite::BackendTexture backendTexture = skgpu::graphite::BackendTextures::MakeDawn(
            wgpuTex
    );

    return reinterpret_cast<KNativePointer>(new skgpu::graphite::BackendTexture(backendTexture));
}


struct SkiaGraphiteDawnSubmitContext {
    std::mutex mutex;
    std::condition_variable cv;
    bool fIsComplete = false;
};

// TODO: something that will create this

SKIKO_EXPORT KInt org_jetbrains_skia_DirectContext__1nDawnSubmit(KNativePointer contextPtr, KNativePointer recorderPtr, KNativePointer submitContextPtr) {
    // TODO: cast pointers to objects
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    skgpu::graphite::Recorder *recorder = reinterpret_cast<skgpu::graphite::Recorder*>(recorderPtr);
    SkiaGraphiteDawnSubmitContext *handle = reinterpret_cast<SkiaGraphiteDawnSubmitContext*>(submitContextPtr);

    std::unique_ptr<skgpu::graphite::Recording> recording = recorder->snap();

    skgpu::graphite::InsertRecordingInfo info;
    info.fFinishedContext = handle;
    info.fFinishedProc   = [](void* ctx, skgpu::CallbackResult result) {
    auto* h = static_cast<SkiaGraphiteDawnSubmitContext*>(ctx);
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        h->fIsComplete=true;
    }
        h->cv.notify_all();  // wake awaitGpuCompletion
    };
    info.fRecording = recording.get();

    if (!context->insertRecording(info)) {
        printf("Context::insertRecording failed\n");
        return -1;
    }

    context->submit(skgpu::graphite::SyncToCpu::kNo);
    return 1;
}


SKIKO_EXPORT void org_jetbrains_skia_DirectContext__1nDawnWait(KNativePointer submitContextPtr) {
    SkiaGraphiteDawnSubmitContext *handle = reinterpret_cast<SkiaGraphiteDawnSubmitContext*>(submitContextPtr);
    std::unique_lock<std::mutex> lock(handle->mutex);

    handle->cv.wait(lock, [handle] { return handle->fIsComplete; });
    handle->fIsComplete = false;
}


SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nCreateSkiaSubmissionHandle() {
    return reinterpret_cast<KNativePointer>(new SkiaGraphiteDawnSubmitContext());
}