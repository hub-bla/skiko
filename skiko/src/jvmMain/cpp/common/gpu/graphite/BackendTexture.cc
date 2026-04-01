#ifdef SK_GRAPHITE
#include <jni.h>
#include "../../interop.hh"
#include "include/gpu/graphite/BackendTexture.h"

static void deleteBackendTexture(skgpu::graphite::BackendTexture *rt) {
    delete rt;
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_gpu_graphite_BackendTextureKt_BackendTexture_1nGetFinalizer
        (JNIEnv *env, jclass jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&deleteBackendTexture));
}
#endif