#include <iostream>
#include <jni.h>
#include "ganesh/GrDirectContext.h"
#include "ganesh/gl/GrGLAssembleInterface.h"
#include "ganesh/gl/GrGLDirectContext.h"
#include "ganesh/gl/GrGLInterface.h"

#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "gpu/graphite/Recorder.h"

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_DirectContextKt__1nMakeGL
  (JNIEnv* env, jclass jclass) {
    return reinterpret_cast<jlong>(GrDirectContexts::MakeGL().release());
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_DirectContext_1jvmKt__1nMakeGLWithInterface
  (JNIEnv* env, jclass jclass, jlong interfacePtr) {
    sk_sp<const GrGLInterface> interface = sk_ref_sp(reinterpret_cast<const GrGLInterface*>(interfacePtr));
    return reinterpret_cast<jlong>(GrDirectContexts::MakeGL(interface).release());
}

#ifdef SK_METAL
#include "ganesh/mtl/GrMtlBackendContext.h"
#include "ganesh/mtl/GrMtlDirectContext.h"

#include "gpu/graphite/mtl/MtlGraphiteTypes_cpp.h"
#include "include/gpu/graphite/mtl/MtlBackendContext.h"

extern "C" JNIEXPORT jlong Java_org_jetbrains_skia_DirectContextKt_1nGraphiteMakeMetal
        (JNIEnv* env, jclass jclass, jlong devicePtr, jlong queuePtr) {
    skgpu::graphite::MtlBackendContext backendContext = {};
    backendContext.fDevice.retain(reinterpret_cast<CFTypeRef>(devicePtr));
    backendContext.fQueue.retain(reinterpret_cast<CFTypeRef>(queuePtr));
    skgpu::graphite::ContextOptions options;

    return reinterpret_cast<jlong>(skgpu::graphite::ContextFactory::MakeMetal(backendContext, options).release());
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_DirectContextKt__1nMakeMetal
  (JNIEnv* env, jclass jclass, long devicePtr, long queuePtr) {
    GrMtlBackendContext backendContext = {};
    GrMTLHandle device = reinterpret_cast<GrMTLHandle>(static_cast<uintptr_t>(devicePtr));
    GrMTLHandle queue = reinterpret_cast<GrMTLHandle>(static_cast<uintptr_t>(queuePtr));
    backendContext.fDevice.retain(device);
    backendContext.fQueue.retain(queue);
    sk_sp<GrDirectContext> instance = GrDirectContexts::MakeMetal(backendContext);
    return reinterpret_cast<jlong>(instance.release());
}
#endif

#ifdef SK_DIRECT3D
#include "ganesh/d3d/GrD3DBackendContext.h"

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_DirectContextKt__1nMakeDirect3D
  (JNIEnv* env, jclass jclass, jlong adapterPtr, jlong devicePtr, jlong queuePtr) {
    GrD3DBackendContext backendContext = {};
    IDXGIAdapter1* adapter = reinterpret_cast<IDXGIAdapter1*>(static_cast<uintptr_t>(adapterPtr));
    ID3D12Device* device = reinterpret_cast<ID3D12Device*>(static_cast<uintptr_t>(devicePtr));
    ID3D12CommandQueue* queue = reinterpret_cast<ID3D12CommandQueue*>(static_cast<uintptr_t>(queuePtr));
    backendContext.fAdapter.retain(adapter);
    backendContext.fDevice.retain(device);
    backendContext.fQueue.retain(queue);
    sk_sp<GrDirectContext> instance = GrDirectContext::MakeDirect3D(backendContext);
    return reinterpret_cast<jlong>(instance.release());
}
#endif //SK_DIRECT3D 

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_DirectContextKt_DirectContext_1nGraphiteSubmit
(JNIEnv* env, jclass jclass, jlong contextPtr, jlong recorderPtr) {
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    skgpu::graphite::Recorder *graphiteRecorder = reinterpret_cast<skgpu::graphite::Recorder*>(recorderPtr);

    std::unique_ptr<skgpu::graphite::Recording> recording = graphiteRecorder->snap();

    skgpu::graphite::InsertRecordingInfo info;
    info.fRecording = recording.get();

    if (!context->insertRecording(info)) {
        printf("Context::insertRecording failed\n");
        return;
    }

    context->submit(skgpu::graphite::SyncToCpu::kYes);
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_DirectContextKt_DirectContext_1nFlushDefault
  (JNIEnv* env, jclass jclass, jlong ptr) {
  GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(ptr));
  context->flush();
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_DirectContextKt_DirectContext_1nFlush
  (JNIEnv* env, jclass jclass, jlong ptr, jlong skSurfacePtr) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(ptr));
    SkSurface* skSurface = reinterpret_cast<SkSurface*>(static_cast<uintptr_t>(skSurfacePtr));
    context->flush(skSurface);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_DirectContextKt_DirectContext_1nGetResourceCacheLimit
  (JNIEnv* env, jclass jclass, jlong ptr) {
     GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(ptr));
     return (jlong) context->getResourceCacheLimit();
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_DirectContextKt_DirectContext_1nSetResourceCacheLimit
  (JNIEnv* env, jclass jclass, jlong ptr, jlong maxResourceBytes) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(ptr));
    context->setResourceCacheLimit((size_t) maxResourceBytes);
}

GrSyncCpu grSyncCpuFromBool(bool syncCpu) {
  if (syncCpu) return GrSyncCpu::kYes;
  return GrSyncCpu::kNo;
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_DirectContextKt__1nFlushAndSubmit
(JNIEnv* env, jclass jclass, jlong ptr, jlong skSurfacePtr, jboolean syncCpu) {
   GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(ptr));
   SkSurface* skSurface = reinterpret_cast<SkSurface*>(static_cast<uintptr_t>(skSurfacePtr));
   context->flushAndSubmit(skSurface, grSyncCpuFromBool(syncCpu));
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_DirectContextKt__1nSubmit
  (JNIEnv* env, jclass jclass, jlong ptr, jboolean syncCpu) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(ptr));
    context->submit(grSyncCpuFromBool(syncCpu));
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_DirectContextKt__1nReset
  (JNIEnv* env, jclass jclass, jlong ptr, jint flags) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(ptr));
    context->resetContext((uint32_t) flags);
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_DirectContextKt__1nAbandon
  (JNIEnv* env, jclass jclass, jlong ptr, jint flags) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(static_cast<uintptr_t>(ptr));
    context->abandonContext();
}
