#ifdef SK_GRAPHITE
#include "../common.h"
#include "include/gpu/graphite/Context.h"
#include "gpu/graphite/Recorder.h"

static void deleteRecorder(skgpu::graphite::Recorder* rt) {
    delete rt;
}

SKIKO_EXPORT KNativePointer jetbrains_skia_graphite_Recorder__1nGetFinalizer
        () {
    return reinterpret_cast<KNativePointer>(reinterpret_cast<uintptr_t>(&deleteRecorder));
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_graphite_Recorder__1nMakeFromContext(KNativePointer contextPtr) {
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    std::unique_ptr<skgpu::graphite::Recorder> graphiteRecorder = context->makeRecorder();
    return reinterpret_cast<KNativePointer>(graphiteRecorder.release());
}
#endif