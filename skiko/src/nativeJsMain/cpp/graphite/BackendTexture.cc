#ifdef SK_GRAPHITE
#include "../common.h"
#include "include/gpu/graphite/BackendTexture.h"

#ifdef SK_METAL
#include "gpu/graphite/mtl/MtlGraphiteTypes_cpp.h"

SKIKO_EXPORT KNativePointer org_jetbrains_skia_graphite_BackendTexture__1nWrapMetalTexture(KNativePointer texturePtr, KInt width, KInt height){
    skgpu::graphite::BackendTexture backendTexture = skgpu::graphite::BackendTextures::MakeMetal(
            SkISize::Make(width, height),
            reinterpret_cast<CFTypeRef>(texturePtr)
    );

    return reinterpret_cast<KNativePointer>(new skgpu::graphite::BackendTexture(backendTexture));
}
#endif

static void deleteBackendTexture(void* ptr) {
    delete reinterpret_cast<skgpu::graphite::BackendTexture*>(ptr);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_graphite_BackendTexture__1nGetFinalizer
        () {
    return reinterpret_cast<KNativePointer>(&deleteBackendTexture);
}
#endif