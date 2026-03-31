#ifdef SK_GRAPHITE
#include <jni.h>
#include "../interop.hh"
#include "include/gpu/graphite/Context.h"
#include "gpu/graphite/Recorder.h"

static void deleteRecorder(skgpu::graphite::Recorder* rt) {
    delete rt;
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_graphite_RecorderKt_Recorder_1nGetFinalizer
        (JNIEnv* env, jclass jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&deleteRecorder));
}


extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_graphite_RecorderKt_Recorder_1nMakeFromContext
        (JNIEnv* env, jclass jclass, jlong contextPtr) {
    skgpu::graphite::Context *context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    std::unique_ptr<skgpu::graphite::Recorder> graphiteRecorder = context->makeRecorder();
    return reinterpret_cast<jlong>(graphiteRecorder.release());
}
#endif