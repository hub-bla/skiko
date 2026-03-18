#import <jawt.h>
#import <jawt_md.h>
#include <fstream>
#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>
#include "include/core/SkData.h"
#include "include/core/SkString.h"
#include "include/core/SkExecutor.h"
#include "core/SkColorSpace.h"
#include "core/SkImageInfo.h"
#include "core/SkSurface.h"
#include "core/SkSurfaceProps.h"

#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/TextureInfo.h"
#include "gpu/graphite/Recorder.h"
#include "gpu/graphite/Surface.h"

#include "include/gpu/graphite/mtl/MtlBackendContext.h"
#include "include/gpu/graphite/mtl/MtlGraphiteUtils.h"
#include "gpu/graphite/mtl/MtlGraphiteTypes.h"

#import "MetalDevice.h"

#include "include/gpu/graphite/PrecompileContext.h"
#include "include/gpu/graphite/precompile/PaintOptions.h"
#include "include/gpu/graphite/precompile/Precompile.h"
#include "include/gpu/graphite/precompile/PrecompileColorFilter.h"
#include "include/gpu/graphite/precompile/PrecompileShader.h"
#include "include/effects/SkGradientShader.h"

#include <set>

using ::skgpu::graphite::PrecompileShaders::GradientShaderFlags;
using ::skgpu::graphite::PrecompileShaders::ImageShaderFlags;
using ::skgpu::graphite::PrecompileShaders::YUVImageShaderFlags;

using ::skgpu::graphite::DepthStencilFlags;
using ::skgpu::graphite::DrawTypeFlags;
using ::skgpu::graphite::PaintOptions;
using ::skgpu::graphite::RenderPassProperties;

PaintOptions solid_srcover() {
    PaintOptions paintOptions;
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions linear_grad_sm_srcover() {
    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::LinearGradient(
            GradientShaderFlags::kSmall)});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions linear_grad_SRGB_sm_med_srcover() {
    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::LinearGradient(
            GradientShaderFlags::kNoLarge,
            {SkGradientShader::Interpolation::InPremul::kNo,
                    SkGradientShader::Interpolation::ColorSpace::kSRGB,
                    SkGradientShader::Interpolation::HueMethod::kShorter})});

    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    paintOptions.setDither(true);

    return paintOptions;
}

PaintOptions xparent_paint_image_premul_hw_and_clamp_srcover() {
    PaintOptions paintOptions;

    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    SkTileMode tm = SkTileMode::kClamp;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {&tm, 1})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    paintOptions.setPaintColorIsOpaque(false);
    return paintOptions;
}

PaintOptions xparent_paint_image_premul_hw_only_srcover() {
    PaintOptions paintOptions;

    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    paintOptions.setPaintColorIsOpaque(false);
    return paintOptions;
}

PaintOptions xparent_paint_srcover() {
    PaintOptions paintOptions;

    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    paintOptions.setPaintColorIsOpaque(false);
    return paintOptions;
}

PaintOptions solid_clear_src_srcover() {
    PaintOptions paintOptions;
    paintOptions.setBlendModes(
            {SkBlendMode::kClear, SkBlendMode::kSrc, SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions solid_src_srcover() {
    PaintOptions paintOptions;
    paintOptions.setBlendModes({SkBlendMode::kSrc, SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions image_premul_no_cubic_srcover() {
    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    SkTileMode tm = SkTileMode::kClamp;
    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {&tm, 1})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions image_premul_hw_only_srcover() {
    PaintOptions paintOptions;

    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions image_premul_clamp_no_cubic_dstin() {
    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    SkTileMode tm = SkTileMode::kClamp;
    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {&tm, 1})});
    paintOptions.setBlendModes({SkBlendMode::kDstIn});
    return paintOptions;
}

PaintOptions image_premul_hw_only_dstin() {
    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setBlendModes({SkBlendMode::kDstIn});
    return paintOptions;
}

PaintOptions yuv_image_srgb_no_cubic_srcover() {
    SkColorInfo ci{
            kRGBA_8888_SkColorType, kPremul_SkAlphaType,
            SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kAdobeRGB)};

    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::YUVImage(
            YUVImageShaderFlags::kExcludeCubic, {&ci, 1})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions yuv_image_srgb_srcover2() {
    SkColorInfo ci{
            kRGBA_8888_SkColorType, kPremul_SkAlphaType,
            SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kAdobeRGB)};

    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::YUVImage(
            YUVImageShaderFlags::kNoCubicNoNonSwizzledHW, {&ci, 1})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions image_premul_no_cubic_src_srcover() {
    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setBlendModes({SkBlendMode::kSrc, SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions image_srgb_no_cubic_src() {
    PaintOptions paintOptions;

    SkColorInfo ci{
            kRGBA_8888_SkColorType, kPremul_SkAlphaType,
            SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kAdobeRGB)};
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setBlendModes({SkBlendMode::kSrc});
    return paintOptions;
}

PaintOptions blend_porter_duff_cf_srcover() {
    PaintOptions paintOptions;
    // kSrcOver will trigger the PorterDuffBlender
    paintOptions.setColorFilters(
            {::skgpu::graphite::PrecompileColorFilters::Blend(
                    {SkBlendMode::kSrcOver})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});

    return paintOptions;
}

PaintOptions image_alpha_hw_only_srcover() {
    PaintOptions paintOptions;

    SkColorInfo ci{kAlpha_8_SkColorType, kUnpremul_SkAlphaType, nullptr};
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions image_alpha_no_cubic_src() {
    PaintOptions paintOptions;

    SkColorInfo ci{kAlpha_8_SkColorType, kUnpremul_SkAlphaType, nullptr};
    SkTileMode tm = SkTileMode::kRepeat;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {&tm, 1})});
    paintOptions.setBlendModes({SkBlendMode::kSrc});
    return paintOptions;
}

PaintOptions image_premul_hw_only_porter_duff_cf_srcover() {
    PaintOptions paintOptions;

    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setColorFilters(
            {::skgpu::graphite::PrecompileColorFilters::Blend(
                    {SkBlendMode::kSrcOver})});

    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}
PaintOptions linear_grad_med_srcover() {
    PaintOptions paintOptions;
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::LinearGradient(
            GradientShaderFlags::kMedium)});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}
PaintOptions image_premul_hw_only_matrix_cf_srcover() {
    PaintOptions paintOptions;

    SkColorInfo ci{kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr};
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setColorFilters(
            {::skgpu::graphite::PrecompileColorFilters::Matrix()});

    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}

PaintOptions image_hw_only_srgb_srcover() {
    PaintOptions paintOptions;

    SkColorInfo ci{
            kRGBA_8888_SkColorType, kPremul_SkAlphaType,
            SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kAdobeRGB)};
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
            ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});

    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}
PaintOptions image_srgb_hw_only_porter_duff_cf_srcover() {
    PaintOptions paintOptions;
    SkColorInfo ci{
        kRGBA_8888_SkColorType, kPremul_SkAlphaType,
        SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, SkNamedGamut::kAdobeRGB)};
    paintOptions.setShaders({::skgpu::graphite::PrecompileShaders::Image(
        ImageShaderFlags::kExcludeCubic, {&ci, 1}, {})});
    paintOptions.setColorFilters(
        {::skgpu::graphite::PrecompileColorFilters::Blend({SkBlendMode::kSrcOver})});
    paintOptions.setBlendModes({SkBlendMode::kSrcOver});
    return paintOptions;
}
 void GraphitePerformPrecompilation(
        std::unique_ptr<skgpu::graphite::PrecompileContext> precompileContext) {

    const RenderPassProperties kBGRA_1_D{DepthStencilFlags::kDepth,
            kBGRA_8888_SkColorType, nullptr, false};

    const RenderPassProperties kBGRA_4_DS{DepthStencilFlags::kDepthStencil,
            kBGRA_8888_SkColorType, nullptr, true};

    constexpr DrawTypeFlags kRRectAndNonAARect = static_cast<DrawTypeFlags>(
            DrawTypeFlags::kAnalyticRRect | DrawTypeFlags::kNonAAFillRect);
    constexpr DrawTypeFlags kQuadAndNonAARect = static_cast<DrawTypeFlags>(
            DrawTypeFlags::kPerEdgeAAQuad | DrawTypeFlags::kNonAAFillRect);

    const struct PrecompileSettings {
        PaintOptions fPaintOptions;
        DrawTypeFlags fDrawTypeFlags = DrawTypeFlags::kNone;
        RenderPassProperties fRenderPassProps;
    } kPrecompileCases[] = {
        {blend_porter_duff_cf_srcover(), DrawTypeFlags::kBitmapText_Mask,  kBGRA_4_DS},
        {solid_srcover(),                DrawTypeFlags::kBitmapText_Mask,  kBGRA_4_DS},
        {xparent_paint_srcover(),        DrawTypeFlags::kBitmapText_Color, kBGRA_4_DS},
        {solid_srcover(),                DrawTypeFlags::kSDFText,          kBGRA_4_DS},
        {solid_srcover(),                DrawTypeFlags::kBitmapText_Mask,  kBGRA_1_D},

        {solid_src_srcover(),       DrawTypeFlags::kSimpleShape,   kBGRA_4_DS},
        {solid_src_srcover(),       DrawTypeFlags::kPerEdgeAAQuad, kBGRA_4_DS},
        {solid_src_srcover(),       DrawTypeFlags::kNonAAFillRect, kBGRA_4_DS},
        {solid_srcover(),           DrawTypeFlags::kAnalyticRRect, kBGRA_4_DS},
        {solid_srcover(),           DrawTypeFlags::kNonAAFillRect, kBGRA_4_DS},
        {solid_clear_src_srcover(), DrawTypeFlags::kNonAAFillRect, kBGRA_4_DS},

        {xparent_paint_srcover(), DrawTypeFlags::kNonAAFillRect, kBGRA_4_DS},
        {xparent_paint_srcover(), DrawTypeFlags::kPerEdgeAAQuad, kBGRA_4_DS},

        {linear_grad_sm_srcover(),  DrawTypeFlags::kNonAAFillRect,        kBGRA_4_DS},
        {linear_grad_sm_srcover(),  DrawTypeFlags::kPerEdgeAAQuad,        kBGRA_4_DS},
        {linear_grad_sm_srcover(),  DrawTypeFlags::kBitmapText_Mask,      kBGRA_4_DS},
        {linear_grad_med_srcover(), DrawTypeFlags::kNonAAFillRect,        kBGRA_4_DS},
        {linear_grad_med_srcover(), DrawTypeFlags::kPerEdgeAAQuad,        kBGRA_4_DS},

        {image_premul_no_cubic_srcover(),                   DrawTypeFlags::kAnalyticRRect, kBGRA_4_DS},
        {image_premul_no_cubic_src_srcover(),               kQuadAndNonAARect,             kBGRA_4_DS},
        {image_premul_hw_only_srcover(),                    kQuadAndNonAARect,             kBGRA_4_DS},
        {image_premul_hw_only_srcover(),                    DrawTypeFlags::kBitmapText_Mask, kBGRA_4_DS},
        {image_premul_hw_only_porter_duff_cf_srcover(),     DrawTypeFlags::kPerEdgeAAQuad, kBGRA_4_DS},
        {image_premul_hw_only_matrix_cf_srcover(),          DrawTypeFlags::kNonAAFillRect, kBGRA_4_DS},
        {image_premul_clamp_no_cubic_dstin(),               kQuadAndNonAARect,             kBGRA_4_DS},
        {xparent_paint_image_premul_hw_and_clamp_srcover(), kQuadAndNonAARect,             kBGRA_4_DS},
        {xparent_paint_image_premul_hw_and_clamp_srcover(), kQuadAndNonAARect,             kBGRA_1_D},
        {solid_srcover(), DrawTypeFlags::kNonSimpleShape, kBGRA_4_DS},

        // BitmapTextColor with simpler key (no AlphaOnlyPaintColor wrapper)
        {xparent_paint_srcover(), DrawTypeFlags::kBitmapText_Color, kBGRA_4_DS},
        {image_srgb_hw_only_porter_duff_cf_srcover(), DrawTypeFlags::kNonAAFillRect, kBGRA_4_DS},
        {solid_srcover(),                DrawTypeFlags::kNonAAFillRect,  kBGRA_1_D},
        {linear_grad_sm_srcover(),       DrawTypeFlags::kNonAAFillRect,  kBGRA_1_D},
        {image_premul_hw_only_srcover(), kQuadAndNonAARect,              kBGRA_1_D},
        {linear_grad_sm_srcover(), DrawTypeFlags::kAnalyticRRect, kBGRA_1_D},
    };

    for (const auto& c : kPrecompileCases) {
        Precompile(precompileContext.get(),
                   c.fPaintOptions,
                   c.fDrawTypeFlags,
                   {&c.fRenderPassProps, 1});
    }
}

namespace fs = std::filesystem;

std::vector<sk_sp<SkData>> loadAllShadersFromDisk(const std::string& dirPath) {
    std::vector<sk_sp<SkData>> loadedShaders;

    if (!fs::exists(dirPath) || !fs::is_directory(dirPath)) {
        NSLog(@"[ShaderCache] ERROR - Directory does not exist: %s", dirPath.c_str());
        return loadedShaders;
    }

    for (const auto& entry : fs::directory_iterator(dirPath)) {
        if (entry.is_regular_file()) {
            std::string path = entry.path().string();

            std::ifstream file(path, std::ios::binary | std::ios::ate);
            if (!file.is_open()) {
                continue;
            }

            std::streamsize size = file.tellg();
            if (size <= 0) continue;

            file.seekg(0, std::ios::beg);

            sk_sp<SkData> data = SkData::MakeUninitialized(static_cast<size_t>(size));

            if (file.read(static_cast<char*>(data->writable_data()), size)) {
                NSLog(@"[ShaderCache] BULK LOAD - Success: %s (%lld bytes)",
                      entry.path().filename().c_str(), size);
                loadedShaders.push_back(std::move(data));
            } else {
                NSLog(@"[ShaderCache] ERROR - Failed to read: %s", path.c_str());
            }
        }
    }

    return loadedShaders;
}

void PrecompilationFromDisk(
    std::unique_ptr<skgpu::graphite::PrecompileContext> precompileContext,
    std::vector<sk_sp<SkData>> pipelines)
{
    if (!precompileContext) {
        NSLog(@"[ShaderCache] ERROR - PrecompileContext is null");
        return;
    }

    int successCount = 0;
    int failCount = 0;

    for (const auto& pipelineData : pipelines) {
        if (!pipelineData || pipelineData->size() == 0) {
            continue;
        }

        bool success = precompileContext->precompile(pipelineData);

        if (success) {
            successCount++;
            NSLog(@"[ShaderCache] PRECOMPILE - Success (%zu bytes)", pipelineData->size());
        } else {
            failCount++;
            NSLog(@"[ShaderCache] PRECOMPILE - Failed to ingest pipeline data");
        }
    }

    NSLog(@"[ShaderCache] COMPLETED - Successfully warmed up %d pipelines (%d failed)",
          successCount, failCount);
}


void HandlePipelineCaching(
        void* context,
        skgpu::graphite::ContextOptions::PipelineCacheOp op,
        const std::string& label,
        unsigned int useCount,
        bool wasPrecompiled,
        sk_sp<SkData> pipelineData)
{
    static std::set<std::string> uniqueLabels;
    static std::mutex countMutex;

    if (op == skgpu::graphite::ContextOptions::PipelineCacheOp::kAddingPipeline && pipelineData) {
        std::lock_guard<std::mutex> lock(countMutex);

        auto [it, inserted] = uniqueLabels.insert(label);
        size_t currentUniqueCount = uniqueLabels.size();

        if (inserted) {
            std::string path = "/Users/hubert.blaszczyk/Desktop/graphite_shaders/" + label + ".bin";
            std::ofstream file(path, std::ios::binary);

            if (file.is_open()) {
                file.write(static_cast<const char*>(pipelineData->data()), pipelineData->size());
                NSLog(@"[ShaderCache] NEW | Unique Count: %zu | Saved: %s",
                        currentUniqueCount, label.c_str());
            }
        } else {
            NSLog(@"[ShaderCache] REPEAT | Unique Count: %zu | Label: %s",
                    currentUniqueCount, label.c_str());
        }
    }
}

extern "C"
{

JNIEXPORT void JNICALL Java_org_jetbrains_skiko_context_GraphiteMetalContextHandler_performPrecompilation(
    JNIEnv* env, jobject contextHandler, jlong contextPtr)
{
    @autoreleasepool {
        // Borrow the raw pointer — do NOT take ownership (no unique_ptr wrapping)
        skgpu::graphite::Context* context =
            reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
        if (!context) return;

        std::unique_ptr<skgpu::graphite::PrecompileContext> precompileContext =
            context->makePrecompileContext();
        if (precompileContext) {
            std::vector<sk_sp<SkData>> pipelines = loadAllShadersFromDisk("/tmp/graphite_shaders");
            PrecompilationFromDisk(std::move(precompileContext), pipelines);
        }
    }
}

JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_context_GraphiteMetalContextHandler_makeMetalContext(
    JNIEnv* env, jobject contextHandler, jlong devicePtr)
{
    @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void *) devicePtr;
        skgpu::graphite::MtlBackendContext backendContext = {};
        backendContext.fDevice.retain((__bridge CFTypeRef) device.adapter);
        backendContext.fQueue.retain((__bridge CFTypeRef) device.queue);
        skgpu::graphite::ContextOptions options;
//        options.fExecutor = SkExecutor::MakeFIFOThreadPool(4).release();
//        options.fPipelineCachingCallback = HandlePipelineCaching;
        return reinterpret_cast<jlong>(skgpu::graphite::ContextFactory::MakeMetal(backendContext, options).release());
    }
}

JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_context_GraphiteMetalContextHandler_createBackendTexture(
        JNIEnv* env, jobject contextHandler, jlong devicePtr, jint width, jint height)
{
    @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void *) devicePtr;

        /// If we have more than `maximumDrawableCount` command buffers inflight, wait until one of them finishes work.
        dispatch_semaphore_wait(device.inflightSemaphore, DISPATCH_TIME_FOREVER);
        id<CAMetalDrawable> currentDrawable = [device.layer nextDrawable];
        if (!currentDrawable) {
            /// Signal semaphore immediately, no command buffer will be commited
            dispatch_semaphore_signal(device.inflightSemaphore);

            return NULL;
        }
        device.drawableHandle = currentDrawable;

        skgpu::graphite::BackendTexture backendTexture = skgpu::graphite::BackendTextures::MakeMetal(
                SkISize::Make(width, height),
                (__bridge CFTypeRef) currentDrawable.texture
        );

        return reinterpret_cast<jlong>(new skgpu::graphite::BackendTexture(backendTexture));
    }
}

JNIEXPORT void JNICALL Java_org_jetbrains_skiko_context_GraphiteMetalContextHandler_finishFrame(
    JNIEnv *env, jobject contextHandler, jlong devicePtr)
{
    @autoreleasepool {
        MetalDevice *device = (__bridge MetalDevice *) (void *) devicePtr;

        id<CAMetalDrawable> currentDrawable = device.drawableHandle;

        if (currentDrawable) {
            id<MTLCommandBuffer> commandBuffer = [device.queue commandBuffer];
            commandBuffer.label = @"Present";

            [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
                /// commands have completed, allow next waiting (if any) to start encoding new work to gpu
                dispatch_semaphore_signal(device.inflightSemaphore);
            }];

            [commandBuffer addScheduledHandler:^(id<MTLCommandBuffer> buffer) {
                int drawableWidth = currentDrawable.texture.width;
                int drawableHeight = currentDrawable.texture.height;

                int layerWidth = device.layer.drawableSize.width;
                int layerHeight = device.layer.drawableSize.height;

                /// Avoid presenting drawable on layer that has already changed size by the moment it was scheduled
                if (drawableWidth == layerWidth && drawableHeight == layerHeight) {
                    [currentDrawable present];
                }
            }];

            [commandBuffer commit];
            device.drawableHandle = nil;
        }
    }
}
} // extern C
