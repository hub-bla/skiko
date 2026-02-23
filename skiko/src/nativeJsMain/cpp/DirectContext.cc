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

SKIKO_EXPORT KNativePointer org_jetbrains_skia_DirectContext__1nGraphiteMakeMetal
        (KNativePointer devicePtr, KNativePointer queuePtr) {
    skgpu::graphite::MtlBackendContext backendContext = {};
    backendContext.fDevice.retain(reinterpret_cast<CFTypeRef>(devicePtr));
    backendContext.fQueue.retain(reinterpret_cast<CFTypeRef>(queuePtr));
    skgpu::graphite::ContextOptions options;

    return static_cast<KNativePointer>(skgpu::graphite::ContextFactory::MakeMetal(backendContext, options).release());
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
#ifdef SK_METAL
    GrMtlBackendContext backendContext = {};
    GrMTLHandle device = reinterpret_cast<GrMTLHandle>((devicePtr));
    GrMTLHandle queue = reinterpret_cast<GrMTLHandle>((queuePtr));
    backendContext.fDevice.retain(device);
    backendContext.fQueue.retain(queue);
    sk_sp<GrDirectContext> instance = GrDirectContexts::MakeMetal(backendContext);
    return static_cast<KNativePointer>(instance.release());
#else
    return nullptr;
#endif // SK_METAL
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

