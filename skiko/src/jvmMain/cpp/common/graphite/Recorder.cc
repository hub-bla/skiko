#ifdef SK_GRAPHITE
#include <jni.h>
#include "../interop.hh"
#include "include/gpu/graphite/Context.h"
#include "gpu/graphite/Recorder.h"
#include "src/gpu/graphite/Surface_Graphite.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/TextureInfo.h"


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

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_graphite_RecorderKt_Recorder_1nSnap
        (JNIEnv* env, jclass jclass, jlong recorderPtr) {
    skgpu::graphite::Recorder *graphiteRecorder = reinterpret_cast<skgpu::graphite::Recorder*>(recorderPtr);
    std::unique_ptr<skgpu::graphite::Recording> recording = graphiteRecorder->snap();

    return reinterpret_cast<jlong>(recording.release());
}

#include "include/core/SkColorSpace.h"
#include "include/core/SkImageInfo.h"

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_graphite_RecorderKt_Recorder_1nMakeDeferredCanvas
    (JNIEnv* env, jclass jclass, jlong recorderPtr, jlong texInfoPtr, jint width, jint height){
    skgpu::graphite::Recorder *recorder = reinterpret_cast<skgpu::graphite::Recorder*>(recorderPtr);
    skgpu::graphite::TextureInfo* texInfo = reinterpret_cast<skgpu::graphite::TextureInfo*>(texInfoPtr);

    SkImageInfo imageInfo = SkImageInfo::Make(
        width,
        height,
        kBGRA_8888_SkColorType,
        kPremul_SkAlphaType,
        SkColorSpace::MakeSRGB()
    );

    SkCanvas* recordingCanvas = recorder->makeDeferredCanvas(imageInfo, *texInfo);

    return reinterpret_cast<jlong>(recordingCanvas);
}

#endif