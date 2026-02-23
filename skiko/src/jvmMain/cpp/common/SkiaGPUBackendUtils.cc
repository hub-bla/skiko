#include <jni.h>
#include "interop.hh"

extern "C" JNIEXPORT jboolean JNICALL Java_org_jetbrains_skia_SkiaGPUBackendUtilsKt_SkiaGPUBackendUtils_1nIsGraphiteEnabled
        () {
#ifdef SK_GRAPHITE
    return true;
#else
    return false;
#endif
}
