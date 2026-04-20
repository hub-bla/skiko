#pragma once

#include "include/core/SkImage.h"
#include "include/gpu/graphite/ImageProvider.h"
#include "include/gpu/graphite/Recorder.h"


class ImageProvider : public skgpu::graphite::ImageProvider {
public:
static sk_sp<ImageProvider> Make();
~ImageProvider() override;

sk_sp<SkImage> findOrCreate(
        skgpu::graphite::Recorder* recorder,
        const SkImage* image,
        SkImage::RequiredProperties requiredProps) override;

private:
ImageProvider();

struct Impl;
std::unique_ptr<Impl> fImpl;
};
