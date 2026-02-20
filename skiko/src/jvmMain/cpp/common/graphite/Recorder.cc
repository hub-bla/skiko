#include <jni.h>
#include "../interop.hh"
#include "gpu/graphite/Recorder.h"

static void deleteRecorder(skgpu::graphite::Recorder* rt) {
    delete rt;
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_graphite_RecorderKt_Recorder_1nGetFinalizer
        (JNIEnv* env, jclass jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&deleteRecorder));
}