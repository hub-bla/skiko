#pragma once

#include "include/core/SkColorSpace.h"
#include "include/gpu/graphite/PrecompileContext.h"
#include "include/gpu/graphite/precompile/PaintOptions.h"
#include "include/gpu/graphite/precompile/Precompile.h"
#include "include/gpu/graphite/precompile/PrecompileShader.h"

#include <array>

namespace skiko::graphite {

// Precompiles a small Compose baseline. This is intentionally not a translation of Impeller's
// ContentContext::Pipelines: each Graphite Precompile call expands into render steps and coverage
// variants. Special cases are warmed from pipeline keys captured from the real workload.
inline void PrecompileComposeBaselinePipelines(skgpu::graphite::PrecompileContext* context,
                                               bool includeMSAA) {
    using namespace skgpu::graphite;

    RenderPassProperties srgb{};
    srgb.fDstCT = kBGRA_8888_SkColorType;
    srgb.fDstCS = SkColorSpace::MakeSRGB();
    RenderPassProperties srgbMSAA = srgb;
    srgbMSAA.fRequiresMSAA = true;

    const std::array<RenderPassProperties, 2> renderPasses{srgb, srgbMSAA};
    const SkSpan<const RenderPassProperties> passes(
            renderPasses.data(), includeMSAA ? renderPasses.size() : 1);

    // Default solid rectangle path, including only the default SrcOver blend mode.
    PaintOptions solid;
    Precompile(context, solid, kNonAAFillRect, passes);

    // The usual gradient and image paths. A single tile mode avoids their option cross-product.
    {
        auto shader = PrecompileShaders::LinearGradient(
                PrecompileShaders::GradientShaderFlags::kMedium);
        PaintOptions options;
        options.setShaders({{std::move(shader)}});
        Precompile(context, options, kNonAAFillRect, passes);
    }

    const std::array<SkColorInfo, 1> imageColorInfos{{
            SkColorInfo(kBGRA_8888_SkColorType, kPremul_SkAlphaType, SkColorSpace::MakeSRGB())}};
    constexpr std::array<SkTileMode, 1> imageTileModes{SkTileMode::kClamp};
    {
        auto shader = PrecompileShaders::Image(PrecompileShaders::ImageShaderFlags::kNoAlphaNoCubic,
                                                imageColorInfos, imageTileModes);
        PaintOptions options;
        options.setShaders({{std::move(shader)}});
        Precompile(context, options, kNonAAFillRect, passes);
    }
}

}  // namespace skiko::graphite
