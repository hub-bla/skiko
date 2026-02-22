#ifdef SK_GRAPHITE
#include "../common.h"
#include "include/gpu/graphite/BackendTexture.h"

#ifdef SK_METAL
#include "gpu/graphite/mtl/MtlGraphiteTypes_cpp.h"

SKIKO_EXPORT KNativePointer jetbrains_skia_graphite_BackendTexture__1nWrapMetalTexture(KNativePointer texturePtr, KInt width, KInt height){
    skgpu::graphite::BackendTexture backendTexture = skgpu::graphite::BackendTextures::MakeMetal(
            SkISize::Make(width, height),
            reinterpret_cast<CFTypeRef>(texturePtr)
    );

    return reinterpret_cast<KNativePointer>(new skgpu::graphite::BackendTexture(backendTexture));
}
#endif

static void deleteBackendTexture(skgpu::graphite::BackendTexture* rt) {
    delete rt;
}

SKIKO_EXPORT KNativePointer jetbrains_skia_graphite_BackendTexture__1nGetFinalizer
        () {
    return reinterpret_cast<KNativePointer>(reinterpret_cast<uintptr_t>(&deleteBackendTexture));
}
#endif