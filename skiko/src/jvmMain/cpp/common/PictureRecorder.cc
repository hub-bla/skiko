#include <algorithm>
#include <iostream>
#include <memory>
#include <unordered_map>
#include <vector>
#include <jni.h>
#include "interop.hh"
#include "SkCanvas.h"
#include "SkDrawable.h"
#include "SkImage.h"
#include "SkM44.h"
#include "SkPicture.h"
#include "SkPictureRecorder.h"
#include "PictureRecorderTrace.hh"
#include "node/RenderNode.h"

enum class RecordedOperationKind {
    SAVE,
    SAVE_LAYER,
    RESTORE,
    CONCAT,
    SET_MATRIX,
    CLIP_RECT,
    CLIP_RRECT,
    CLIP_PATH,
    DRAW_PAINT,
    DRAW_RECT,
    DRAW_RRECT,
    DRAW_DRRECT,
    DRAW_OVAL,
    DRAW_ARC,
    DRAW_PATH,
    DRAW_TEXT_BLOB,
    DRAW_IMAGE,
    DRAW_IMAGE_RECT,
    DRAW_VERTICES,
    DRAW_DRAWABLE,
    DRAW_PICTURE,
    DRAW_POINTS,
    DRAW_PATCH,
    DRAW_ANNOTATION,
    DRAW_EDGE_AA_QUAD,
};

struct RecordedOperation {
    RecordedOperationKind kind;
    int depth;
};

struct RecordedPicture {
    uint32_t pictureId;
    int depth;
};

enum class RecordedDrawableKind {
    UNKNOWN,
    RENDER_NODE,
};

struct RecordedDrawable {
    RecordedDrawableKind kind;
    uint32_t generationId;
    int depth;
    int operationStartIndex;
    int operationEndIndex;
};

class OperationTracingCanvas final : public SkCanvas {
public:
    OperationTracingCanvas(
        SkCanvas* target,
        std::vector<RecordedOperation>* operations,
        std::vector<RecordedPicture>* pictures,
        std::vector<RecordedDrawable>* drawables,
        int initialDepth = 0
    )
        : SkCanvas()
        , fTarget(target)
        , fOperations(operations)
        , fPictures(pictures)
        , fDrawables(drawables)
        , fDepth(initialDepth) {}

    void recordPictureDraw(const SkPicture* picture) {
        if (picture != nullptr) {
            fPictures->push_back({picture->uniqueID(), fDepth});
        }
    }

protected:
    void willSave() override {
        ++fDepth;
        fOperations->push_back({RecordedOperationKind::SAVE, fDepth});
        if (fTarget != nullptr) {
            fTarget->save();
        }
    }

    SaveLayerStrategy getSaveLayerStrategy(const SaveLayerRec& rec) override {
        ++fDepth;
        fOperations->push_back({RecordedOperationKind::SAVE_LAYER, fDepth});
        if (fTarget != nullptr) {
            fTarget->saveLayer(rec);
        }
        return kNoLayer_SaveLayerStrategy;
    }

    void willRestore() override {
        fDepth = std::max(0, fDepth - 1);
        fOperations->push_back({RecordedOperationKind::RESTORE, fDepth});
        if (fTarget != nullptr) {
            fTarget->restore();
        }
    }

    void didConcat44(const SkM44& matrix) override {
        fOperations->push_back({RecordedOperationKind::CONCAT, fDepth});
        if (fTarget != nullptr) {
            fTarget->concat(matrix);
        }
    }

    void didSetM44(const SkM44& matrix) override {
        fOperations->push_back({RecordedOperationKind::SET_MATRIX, fDepth});
        if (fTarget != nullptr) {
            fTarget->setMatrix(matrix);
        }
    }

    void onClipRect(const SkRect& rect, SkClipOp op, ClipEdgeStyle edgeStyle) override {
        fOperations->push_back({RecordedOperationKind::CLIP_RECT, fDepth});
        if (fTarget != nullptr) {
            fTarget->clipRect(rect, op, edgeStyle);
        }
    }

    void onClipRRect(const SkRRect& rrect, SkClipOp op, ClipEdgeStyle edgeStyle) override {
        fOperations->push_back({RecordedOperationKind::CLIP_RRECT, fDepth});
        if (fTarget != nullptr) {
            fTarget->clipRRect(rrect, op, edgeStyle);
        }
    }

    void onClipPath(const SkPath& path, SkClipOp op, ClipEdgeStyle edgeStyle) override {
        fOperations->push_back({RecordedOperationKind::CLIP_PATH, fDepth});
        if (fTarget != nullptr) {
            fTarget->clipPath(path, op, edgeStyle);
        }
    }

    void onDrawPaint(const SkPaint& paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_PAINT, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawPaint(paint);
        }
    }

    void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_RECT, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawRect(rect, paint);
        }
    }

    void onDrawRRect(const SkRRect& rrect, const SkPaint& paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_RRECT, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawRRect(rrect, paint);
        }
    }

    void onDrawDRRect(const SkRRect& outer, const SkRRect& inner, const SkPaint& paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_DRRECT, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawDRRect(outer, inner, paint);
        }
    }

    void onDrawOval(const SkRect& rect, const SkPaint& paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_OVAL, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawOval(rect, paint);
        }
    }

    void onDrawArc(const SkRect& rect, SkScalar startAngle, SkScalar sweepAngle, bool useCenter, const SkPaint& paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_ARC, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawArc(rect, startAngle, sweepAngle, useCenter, paint);
        }
    }

    void onDrawPath(const SkPath& path, const SkPaint& paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_PATH, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawPath(path, paint);
        }
    }

    void onDrawImage2(const SkImage* image, SkScalar dx, SkScalar dy, const SkSamplingOptions& sampling, const SkPaint* paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_IMAGE, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawImage(sk_ref_sp(image), dx, dy, sampling, paint);
        }
    }

    void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override {
        fOperations->push_back({RecordedOperationKind::DRAW_DRAWABLE, fDepth});
        const int operationStartIndex = static_cast<int>(fOperations->size());
        if (drawable != nullptr) {
            fDrawables->push_back({
                classifyDrawable(drawable),
                drawable->getGenerationID(),
                fDepth,
                operationStartIndex,
                operationStartIndex
            });
            if (fTarget != nullptr) {
                OperationTracingCanvas analysisCanvas(nullptr, fOperations, fPictures, fDrawables, fDepth);
                drawable->draw(&analysisCanvas, matrix);
                fTarget->drawDrawable(drawable, matrix);
            } else {
                drawable->draw(this, matrix);
            }
            fDrawables->back().operationEndIndex = static_cast<int>(fOperations->size());
        } else if (fTarget != nullptr) {
            fTarget->drawDrawable(drawable, matrix);
        }
    }

    void onDrawPicture(const SkPicture* picture, const SkMatrix* matrix, const SkPaint* paint) override {
        fOperations->push_back({RecordedOperationKind::DRAW_PICTURE, fDepth});
        if (fTarget != nullptr) {
            fTarget->drawPicture(picture, matrix, paint);
        }
    }

private:
    static RecordedDrawableKind classifyDrawable(SkDrawable* drawable) {
        return dynamic_cast<skiko::node::RenderNode*>(drawable) != nullptr
            ? RecordedDrawableKind::RENDER_NODE
            : RecordedDrawableKind::UNKNOWN;
    }

    SkCanvas* fTarget;
    std::vector<RecordedOperation>* fOperations;
    std::vector<RecordedPicture>* fPictures;
    std::vector<RecordedDrawable>* fDrawables;
    int fDepth = 0;
};

static std::unordered_map<SkCanvas*, OperationTracingCanvas*>& tracingCanvases();

struct ManagedPictureRecorder {
    SkPictureRecorder recorder;
    std::vector<RecordedOperation> recordedOperations;
    std::vector<RecordedPicture> recordedPictures;
    std::vector<RecordedDrawable> recordedDrawables;
    std::unique_ptr<OperationTracingCanvas> tracingCanvas;
    SkCanvas* exposedCanvas = nullptr;

    SkCanvas* beginRecording(const SkRect& bounds, SkBBHFactory* factory, bool traceOperations) {
        recordedOperations.clear();
        recordedPictures.clear();
        recordedDrawables.clear();
        if (exposedCanvas != nullptr) {
            tracingCanvases().erase(exposedCanvas);
        }
        tracingCanvas.reset();
        SkCanvas* canvas = recorder.beginRecording(bounds, factory);
        if (traceOperations) {
            tracingCanvas = std::make_unique<OperationTracingCanvas>(
                canvas,
                &recordedOperations,
                &recordedPictures,
                &recordedDrawables
            );
            exposedCanvas = tracingCanvas.get();
            tracingCanvases()[exposedCanvas] = tracingCanvas.get();
        } else {
            exposedCanvas = canvas;
        }
        return exposedCanvas;
    }

    void clearActiveCanvas() {
        if (exposedCanvas != nullptr) {
            tracingCanvases().erase(exposedCanvas);
        }
        tracingCanvas.reset();
        exposedCanvas = nullptr;
    }
};

static void deletePictureRecorder(ManagedPictureRecorder* pr) {
    delete pr;
}

static std::unordered_map<SkCanvas*, OperationTracingCanvas*>& tracingCanvases() {
    static std::unordered_map<SkCanvas*, OperationTracingCanvas*> canvases;
    return canvases;
}

void skikoRecordPictureDraw(SkCanvas* canvas, const SkPicture* picture) {
    const auto iterator = tracingCanvases().find(canvas);
    if (iterator != tracingCanvases().end()) {
        iterator->second->recordPictureDraw(picture);
    }
}

bool skikoIsOperationTracingCanvas(SkCanvas* canvas) {
    return tracingCanvases().find(canvas) != tracingCanvases().end();
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureRecorderKt_PictureRecorder_1nMake
  (JNIEnv* env, jclass jclass) {
    ManagedPictureRecorder* instance = new ManagedPictureRecorder();
    return reinterpret_cast<jlong>(instance);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureRecorderKt_PictureRecorder_1nGetFinalizer
  (JNIEnv* env, jclass jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&deletePictureRecorder));
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nBeginRecording
  (JNIEnv* env, jclass jclass, jlong ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jlong bbh) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    SkBBHFactory* factory = reinterpret_cast<SkBBHFactory*>(static_cast<uintptr_t>(bbh));
    SkCanvas* canvas = instance->beginRecording(SkRect::MakeLTRB(left, top, right, bottom), factory, false);
    return reinterpret_cast<jlong>(canvas);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nBeginRecordingWithOperationTrace
  (JNIEnv* env, jclass jclass, jlong ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jlong bbh) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    SkBBHFactory* factory = reinterpret_cast<SkBBHFactory*>(static_cast<uintptr_t>(bbh));
    SkCanvas* canvas = instance->beginRecording(SkRect::MakeLTRB(left, top, right, bottom), factory, true);
    return reinterpret_cast<jlong>(canvas);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nGetRecordingCanvas
  (JNIEnv* env, jclass jclass, jlong ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    SkCanvas* canvas = instance->exposedCanvas != nullptr ? instance->exposedCanvas : instance->recorder.getRecordingCanvas();
    return reinterpret_cast<jlong>(canvas);
}

extern "C" JNIEXPORT jint JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nGetRecordedOperationCount
  (JNIEnv* env, jclass jclass, jlong ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    return static_cast<jint>(instance->recordedOperations.size());
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nGetRecordedOperations
  (JNIEnv* env, jclass jclass, jlong ptr, jintArray joperations) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    jsize expectedSize = static_cast<jsize>(instance->recordedOperations.size() * 2);
    jsize size = env->GetArrayLength(joperations);
    if (size < expectedSize) {
        return;
    }
    std::vector<jint> raw(expectedSize);
    for (size_t i = 0; i < instance->recordedOperations.size(); ++i) {
        raw[i * 2] = static_cast<jint>(instance->recordedOperations[i].kind);
        raw[i * 2 + 1] = static_cast<jint>(instance->recordedOperations[i].depth);
    }
    env->SetIntArrayRegion(joperations, 0, expectedSize, raw.data());
}

extern "C" JNIEXPORT jint JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nGetRecordedPictureCount
  (JNIEnv* env, jclass jclass, jlong ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    return static_cast<jint>(instance->recordedPictures.size());
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nGetRecordedPictures
  (JNIEnv* env, jclass jclass, jlong ptr, jintArray jpictures) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    jsize expectedSize = static_cast<jsize>(instance->recordedPictures.size() * 2);
    jsize size = env->GetArrayLength(jpictures);
    if (size < expectedSize) {
        return;
    }
    std::vector<jint> raw(expectedSize);
    for (size_t i = 0; i < instance->recordedPictures.size(); ++i) {
        raw[i * 2] = static_cast<jint>(instance->recordedPictures[i].pictureId);
        raw[i * 2 + 1] = static_cast<jint>(instance->recordedPictures[i].depth);
    }
    env->SetIntArrayRegion(jpictures, 0, expectedSize, raw.data());
}

extern "C" JNIEXPORT jint JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nGetRecordedDrawableCount
  (JNIEnv* env, jclass jclass, jlong ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    return static_cast<jint>(instance->recordedDrawables.size());
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nGetRecordedDrawables
  (JNIEnv* env, jclass jclass, jlong ptr, jintArray jdrawables) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    jsize expectedSize = static_cast<jsize>(instance->recordedDrawables.size() * 5);
    jsize size = env->GetArrayLength(jdrawables);
    if (size < expectedSize) {
        return;
    }
    std::vector<jint> raw(expectedSize);
    for (size_t i = 0; i < instance->recordedDrawables.size(); ++i) {
        raw[i * 5] = static_cast<jint>(instance->recordedDrawables[i].kind);
        raw[i * 5 + 1] = static_cast<jint>(instance->recordedDrawables[i].generationId);
        raw[i * 5 + 2] = static_cast<jint>(instance->recordedDrawables[i].depth);
        raw[i * 5 + 3] = static_cast<jint>(instance->recordedDrawables[i].operationStartIndex);
        raw[i * 5 + 4] = static_cast<jint>(instance->recordedDrawables[i].operationEndIndex);
    }
    env->SetIntArrayRegion(jdrawables, 0, expectedSize, raw.data());
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nFinishRecordingAsPicture
  (JNIEnv* env, jclass jclass, jlong ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    SkPicture* picture = instance->recorder.finishRecordingAsPicture().release();
    instance->clearActiveCanvas();
    return reinterpret_cast<jlong>(picture);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nFinishRecordingAsPictureWithCull
  (JNIEnv* env, jclass jclass, jlong ptr, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    SkPicture* picture = instance->recorder.finishRecordingAsPictureWithCull(SkRect::MakeLTRB(left, top, right, bottom)).release();
    instance->clearActiveCanvas();
    return reinterpret_cast<jlong>(picture);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureRecorderKt__1nFinishRecordingAsDrawable
  (JNIEnv* env, jclass jclass, jlong ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>(static_cast<uintptr_t>(ptr));
    SkDrawable* drawable = instance->recorder.finishRecordingAsDrawable().release();
    instance->clearActiveCanvas();
    return reinterpret_cast<jlong>(drawable);
}