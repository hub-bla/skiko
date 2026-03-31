#ifdef SK_GRAPHITE
#include "../common.h"
#include "include/gpu/graphite/Context.h"
#include "gpu/graphite/Recorder.h"

static void deleteRecorder(void* ptr) {
    delete reinterpret_cast<skgpu::graphite::Recorder*>(ptr);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_graphite_Recorder__1nGetFinalizer
        () {
    return reinterpret_cast<KNativePointer>(&deleteRecorder);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_graphite_Recorder__1nMakeFromContext(KNativePointer contextPtr) {
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    std::unique_ptr<skgpu::graphite::Recorder> graphiteRecorder = context->makeRecorder();
    return reinterpret_cast<KNativePointer>(graphiteRecorder.release());
}
#endif