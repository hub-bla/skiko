#include "common.h"
#include "GraphiteImageProvider.hh"
#include "GraphitePrecompile.hh"

#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/PrecompileContext.h"
#include "include/gpu/graphite/Recorder.h"
#include "include/core/SkData.h"
#include "include/private/SkDebug.h"
#include "include/gpu/graphite/mtl/MtlBackendContext.h"
#include "src/gpu/mtl/MtlShaderCapture.h"

namespace {

void capture_graphite_pipeline(skgpu::graphite::ContextOptions::PipelineCallbackContext,
                               skgpu::graphite::ContextOptions::PipelineCacheOp op,
                               const std::string& label,
                               uint32_t uniqueKeyHash,
                               bool fromPrecompile,
                               sk_sp<SkData> pipelineData) {
    if (op != skgpu::graphite::ContextOptions::PipelineCacheOp::kAddingPipeline) {
        return;
    }

    // Pipeline replay and the baseline precompile API also add pipelines to the global cache.
    // Capturing those would feed precompiled keys back into the next capture indefinitely. Only
    // persist pipelines discovered by normal rendering.
    if (fromPrecompile) {
        SkDebugf("[Graphite] Capture ignoring precompiled pipeline key=%08x: %s\n",
                 uniqueKeyHash,
                 label.c_str());
        return;
    }
    if (!pipelineData) {
        SkDebugf("[Graphite] Capture cannot serialize runtime pipeline key=%08x: %s\n",
                 uniqueKeyHash,
                 label.c_str());
        return;
    }

    SkDebugf("[Graphite] Capture saving runtime pipeline key=%08x bytes=%zu: %s\n",
             uniqueKeyHash,
             pipelineData->size(),
             label.c_str());
    skgpu::MtlShaderCapture::CapturePipelineKey(pipelineData.get());
}

}  // namespace

static void deleteGraphiteContext(skgpu::graphite::Context* context) {
    delete context;
}

static void deletePrecompileContext(skgpu::graphite::PrecompileContext* context) {
    delete context;
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_gpu_graphite_GraphiteContext__1nGetFinalizer() {
    return reinterpret_cast<KNativePointer>(&deleteGraphiteContext);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_gpu_graphite_GraphiteContext__1nMakeMetal(
        KNativePointer devicePtr, KNativePointer queuePtr, KInteropPointer pathPtr) {
    skgpu::graphite::MtlBackendContext backendContext{};
    backendContext.fDevice.retain(reinterpret_cast<CFTypeRef>(devicePtr));
    backendContext.fQueue.retain(reinterpret_cast<CFTypeRef>(queuePtr));
    const char* path = reinterpret_cast<const char*>(pathPtr);
    backendContext.fPath = path ? std::string(path) : std::string();

    skgpu::graphite::ContextOptions options{};
    options.fRequireOrderedRecordings = true;
    if (skgpu::MtlShaderCapture::Enabled()) {
        options.fPipelineCachingCallback = capture_graphite_pipeline;
    }
    return reinterpret_cast<KNativePointer>(
            skgpu::graphite::ContextFactory::MakeMetal(backendContext, options).release());
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_gpu_graphite_GraphiteContext__1nMakeRecorder(
        KNativePointer contextPtr) {
    auto context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    skgpu::graphite::RecorderOptions options{};
    options.fImageProvider = SkikoGraphiteImageProvider::Make();
    return reinterpret_cast<KNativePointer>(context->makeRecorder(options).release());
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_gpu_graphite_GraphiteContext__1nMakePrecompileContext(
        KNativePointer contextPtr) {
    auto context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    return reinterpret_cast<KNativePointer>(context->makePrecompileContext().release());
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_gpu_graphite_PrecompileContext__1nGetFinalizer() {
    return reinterpret_cast<KNativePointer>(&deletePrecompileContext);
}

SKIKO_EXPORT KBoolean org_jetbrains_skia_gpu_graphite_PrecompileContext__1nPrecompile(
        KNativePointer contextPtr, KInteropPointer serializedPipelineKey, KInt size) {
    if (!serializedPipelineKey || size <= 0) {
        return false;
    }
    auto context = reinterpret_cast<skgpu::graphite::PrecompileContext*>(contextPtr);
    auto pipelineKey = SkData::MakeWithCopy(serializedPipelineKey, size);
    const auto externalFormat = context->containsExternalFormat(pipelineKey);
    if (externalFormat != skgpu::graphite::PrecompileContext::ExternalFormatResult::kNoExternalFormat) {
        SkDebugf("[Graphite] Skipping %s pipeline key\n",
                 externalFormat == skgpu::graphite::PrecompileContext::ExternalFormatResult::kHasExternalFormat
                         ? "external-format"
                         : "invalid");
        return false;
    }
    uint32_t uniqueKeyHash = 0;
    const std::string label = context->getPipelineLabel(pipelineKey, &uniqueKeyHash);
    SkDebugf("[Graphite] Native precompile BEGIN key=%08x bytes=%d: %s\n",
             uniqueKeyHash,
             size,
             label.c_str());
    const bool accepted = context->precompile(std::move(pipelineKey));
    SkDebugf("[Graphite] Native precompile END key=%08x accepted=%s\n",
             uniqueKeyHash,
             accepted ? "true" : "false");
    return accepted;
}

SKIKO_EXPORT void org_jetbrains_skia_gpu_graphite_PrecompileContext__1nPrecompileImpellerLikePipelines(
        KNativePointer contextPtr, KBoolean includeMSAA) {
    auto context = reinterpret_cast<skgpu::graphite::PrecompileContext*>(contextPtr);
    skiko::graphite::PrecompileComposeBaselinePipelines(context, includeMSAA);
}

SKIKO_EXPORT void org_jetbrains_skia_gpu_graphite_GraphiteContext__1nInsertRecording(
        KNativePointer contextPtr, KNativePointer recordingPtr) {
    auto context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    skgpu::graphite::InsertRecordingInfo info{};
    info.fRecording = reinterpret_cast<skgpu::graphite::Recording*>(recordingPtr);
    context->insertRecording(info);
}

SKIKO_EXPORT void org_jetbrains_skia_gpu_graphite_GraphiteContext__1nSubmit(
        KNativePointer contextPtr, KBoolean syncCpu) {
    auto context = reinterpret_cast<skgpu::graphite::Context*>(contextPtr);
    context->submit(skgpu::graphite::SubmitInfo(
            syncCpu ? skgpu::graphite::SyncToCpu::kYes : skgpu::graphite::SyncToCpu::kNo));
}
