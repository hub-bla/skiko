#pragma once

#include "include/core/SkImage.h"
#include "include/core/SkTiledImageUtils.h"

#include <cstdint>

class __attribute__((visibility("default"))) ImageKey {
public:
    ImageKey(const SkImage* image, bool mipmapped);

    uint32_t hash() const { return fValues[0]; }

    bool operator==(const ImageKey& other) const;
    bool operator!=(const ImageKey& other) const;

    struct Hash {
        size_t operator()(const ImageKey& key) const { return key.hash(); }
    };

private:
    static const int kNumValues = SkTiledImageUtils::kNumImageKeyValues + 2;
    uint32_t fValues[kNumValues];
};
