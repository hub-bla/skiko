#include "common.h"

SKIKO_EXPORT KBoolean org_jetbrains_skia_SkiaGPUBackendUtils__1nIsGraphiteEnabled
    () {
#ifdef SK_GRAPHITE
        return true;
#else
        return false;
#endif
    }
