#include <algorithm>
#include <iostream>
#include <memory>
#include <unordered_map>
#include <vector>
#include "SkCanvas.h"
#include "SkPicture.h"
#include "SkDrawable.h"
#include "SkImage.h"
#include "SkM44.h"
#include "SkPictureRecorder.h"
#include "PictureRecorderTrace.h"
#include "node/RenderNode.h"
#include "common.h"

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
        return  RecordedDrawableKind::RENDER_NODE;
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

SKIKO_EXPORT KNativePointer org_jetbrains_skia_PictureRecorder__1nMake
  () {
    ManagedPictureRecorder* instance = new ManagedPictureRecorder();
    return reinterpret_cast<KNativePointer>(instance);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_PictureRecorder__1nGetFinalizer
  () {
    return reinterpret_cast<KNativePointer>((&deletePictureRecorder));
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_PictureRecorder__1nBeginRecording
  (KNativePointer ptr, KFloat left, KFloat top, KFloat right, KFloat bottom, KNativePointer bbh) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    SkBBHFactory* factory = reinterpret_cast<SkBBHFactory*>(bbh);
    SkCanvas* canvas = instance->beginRecording(SkRect::MakeLTRB(left, top, right, bottom), factory, false);
    return reinterpret_cast<KNativePointer>(canvas);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_PictureRecorder__1nBeginRecordingWithOperationTrace
  (KNativePointer ptr, KFloat left, KFloat top, KFloat right, KFloat bottom, KNativePointer bbh) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    SkBBHFactory* factory = reinterpret_cast<SkBBHFactory*>(bbh);
    SkCanvas* canvas = instance->beginRecording(SkRect::MakeLTRB(left, top, right, bottom), factory, true);
    return reinterpret_cast<KNativePointer>(canvas);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_PictureRecorder__1nGetRecordingCanvas
  (KNativePointer ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    SkCanvas* canvas = instance->exposedCanvas != nullptr ? instance->exposedCanvas : instance->recorder.getRecordingCanvas();
    return reinterpret_cast<KNativePointer>(canvas);
}

SKIKO_EXPORT KInt org_jetbrains_skia_PictureRecorder__1nGetRecordedOperationCount
  (KNativePointer ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    return static_cast<KInt>(instance->recordedOperations.size());
}

SKIKO_EXPORT void org_jetbrains_skia_PictureRecorder__1nGetRecordedOperations
  (KNativePointer ptr, KInt* operations) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    for (size_t i = 0; i < instance->recordedOperations.size(); ++i) {
        operations[i * 2] = static_cast<KInt>(instance->recordedOperations[i].kind);
        operations[i * 2 + 1] = static_cast<KInt>(instance->recordedOperations[i].depth);
    }
}

SKIKO_EXPORT KInt org_jetbrains_skia_PictureRecorder__1nGetRecordedPictureCount
  (KNativePointer ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    return static_cast<KInt>(instance->recordedPictures.size());
}

SKIKO_EXPORT void org_jetbrains_skia_PictureRecorder__1nGetRecordedPictures
  (KNativePointer ptr, KInt* pictures) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    for (size_t i = 0; i < instance->recordedPictures.size(); ++i) {
        pictures[i * 2] = static_cast<KInt>(instance->recordedPictures[i].pictureId);
        pictures[i * 2 + 1] = static_cast<KInt>(instance->recordedPictures[i].depth);
    }
}

SKIKO_EXPORT KInt org_jetbrains_skia_PictureRecorder__1nGetRecordedDrawableCount
  (KNativePointer ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    return static_cast<KInt>(instance->recordedDrawables.size());
}

SKIKO_EXPORT void org_jetbrains_skia_PictureRecorder__1nGetRecordedDrawables
  (KNativePointer ptr, KInt* drawables) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    for (size_t i = 0; i < instance->recordedDrawables.size(); ++i) {
        drawables[i * 5] = static_cast<KInt>(instance->recordedDrawables[i].kind);
        drawables[i * 5 + 1] = static_cast<KInt>(instance->recordedDrawables[i].generationId);
        drawables[i * 5 + 2] = static_cast<KInt>(instance->recordedDrawables[i].depth);
        drawables[i * 5 + 3] = static_cast<KInt>(instance->recordedDrawables[i].operationStartIndex);
        drawables[i * 5 + 4] = static_cast<KInt>(instance->recordedDrawables[i].operationEndIndex);
    }
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_PictureRecorder__1nFinishRecordingAsPicture
  (KNativePointer ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    SkPicture* picture = instance->recorder.finishRecordingAsPicture().release();
    instance->clearActiveCanvas();
    return reinterpret_cast<KNativePointer>(picture);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_PictureRecorder__1nFinishRecordingAsPictureWithCull
  (KNativePointer ptr, KFloat left, KFloat top, KFloat right, KFloat bottom) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    SkPicture* picture = instance->recorder.finishRecordingAsPictureWithCull(SkRect::MakeLTRB(left, top, right, bottom)).release();
    instance->clearActiveCanvas();
    return reinterpret_cast<KNativePointer>(picture);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_PictureRecorder__1nFinishRecordingAsDrawable
  (KNativePointer ptr) {
    ManagedPictureRecorder* instance = reinterpret_cast<ManagedPictureRecorder*>((ptr));
    SkDrawable* drawable = instance->recorder.finishRecordingAsDrawable().release();
    instance->clearActiveCanvas();
    return reinterpret_cast<KNativePointer>(drawable);
}
