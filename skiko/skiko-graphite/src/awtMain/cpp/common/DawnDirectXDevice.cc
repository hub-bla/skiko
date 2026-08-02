#include <jni.h>

#if !defined(SK_DIRECT3D)
extern "C" JNIEXPORT jlong JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nGetFinalizer(JNIEnv*, jclass) {
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nMake(JNIEnv*, jclass, jobject) {
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nMakeGraphiteContext(
        JNIEnv*, jclass, jlong) {
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nResize(
        JNIEnv*, jclass, jlong, jint, jint) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nAcquireTexture(JNIEnv*, jclass, jlong) {
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_jetbrains_skia_gpu_graphite_DawnDirectXDeviceKt__1nPresent(JNIEnv*, jclass, jlong, jboolean) {}
#endif
