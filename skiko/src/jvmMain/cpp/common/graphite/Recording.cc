#ifdef SK_GRAPHITE
#include <jni.h>
#include "../interop.hh"
#include "include/gpu/graphite/Recorder.h"

static void deleteRecording(skgpu::graphite::Recording* rt) {
    delete rt;
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_graphite_RecordingKt_Recording_1nGetFinalizer
        (JNIEnv* env, jclass jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&deleteRecording));
}
#endif