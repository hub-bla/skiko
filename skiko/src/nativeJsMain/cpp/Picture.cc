#include <iostream>
#include "SkData.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkDrawable.h"
#include "SkPicture.h"
#include "include/core/SkPictureRecorder.h"
#include "SkShader.h"
#include "common.h"

namespace {

class ChunkRecordingCanvas final : public SkCanvas {
public:
    ChunkRecordingCanvas(SkCanvas* target, int operationStartIndex, int operationEndExclusive)
        : SkCanvas()
        , fTarget(target)
        , fOperationStartIndex(operationStartIndex)
        , fOperationEndExclusive(operationEndExclusive) {}

    void finish() {
        fTarget->restoreToCount(1);
    }

protected:
    void willSave() override {
        recordStatefulOperation([this] { fTarget->save(); });
    }

    SkCanvas::SaveLayerStrategy getSaveLayerStrategy(const SkCanvas::SaveLayerRec& rec) override {
        recordStatefulOperation([this, &rec] { fTarget->saveLayer(rec); }, false);
        return SkCanvas::kNoLayer_SaveLayerStrategy;
    }

    void willRestore() override {
        recordStatefulOperation([this] {
            if (fTarget->getSaveCount() > 1) {
                fTarget->restore();
            }
        });
    }

    void didConcat44(const SkM44& matrix) override {
        recordStatefulOperation([this, &matrix] { fTarget->concat(matrix); });
    }

    void didSetM44(const SkM44& matrix) override {
        recordStatefulOperation([this, &matrix] { fTarget->setMatrix(matrix); });
    }

    void onClipRect(const SkRect& rect, SkClipOp op, SkCanvas::ClipEdgeStyle edgeStyle) override {
        recordStatefulOperation([this, &rect, op, edgeStyle] { fTarget->clipRect(rect, op, edgeStyle); });
    }

    void onClipRRect(const SkRRect& rrect, SkClipOp op, SkCanvas::ClipEdgeStyle edgeStyle) override {
        recordStatefulOperation([this, &rrect, op, edgeStyle] { fTarget->clipRRect(rrect, op, edgeStyle); });
    }

    void onClipPath(const SkPath& path, SkClipOp op, SkCanvas::ClipEdgeStyle edgeStyle) override {
        recordStatefulOperation([this, &path, op, edgeStyle] { fTarget->clipPath(path, op, edgeStyle); });
    }

    void onDrawPaint(const SkPaint& paint) override {
        recordDrawOperation([this, &paint] { fTarget->drawPaint(paint); });
    }

    void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
        recordDrawOperation([this, &rect, &paint] { fTarget->drawRect(rect, paint); });
    }

    void onDrawRRect(const SkRRect& rrect, const SkPaint& paint) override {
        recordDrawOperation([this, &rrect, &paint] { fTarget->drawRRect(rrect, paint); });
    }

    void onDrawPath(const SkPath& path, const SkPaint& paint) override {
        recordDrawOperation([this, &path, &paint] { fTarget->drawPath(path, paint); });
    }

    void onDrawArc(const SkRect& oval, SkScalar startAngle, SkScalar sweepAngle, bool useCenter, const SkPaint& paint) override {
        recordDrawOperation([this, &oval, startAngle, sweepAngle, useCenter, &paint] {
            fTarget->drawArc(oval, startAngle, sweepAngle, useCenter, paint);
        });
    }

    void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override {
        const int currentIndex = fCurrentOperationIndex++;
        if (currentIndex >= fOperationEndExclusive) {
            return;
        }
        if (drawable == nullptr) {
            if (currentIndex >= fOperationStartIndex) {
                fTarget->drawDrawable(drawable, matrix);
            }
            return;
        }

        // Match the recursive drawable trace used by chunk planning: flatten drawable-backed
        // content into the extracted picture so chunk op ranges stay aligned with traced ops and
        // do not retain live drawable dependencies.
        drawable->draw(this, matrix);
    }

private:
    template <typename Callback>
    void recordStatefulOperation(Callback callback, bool supportedInPrefix = true) {
        const int currentIndex = fCurrentOperationIndex++;
        if (currentIndex >= fOperationEndExclusive) {
            return;
        }
        if (currentIndex < fOperationStartIndex) {
            if (supportedInPrefix) {
                callback();
            }
            return;
        }
        callback();
    }

    template <typename Callback>
    void recordDrawOperation(Callback callback) {
        const int currentIndex = fCurrentOperationIndex++;
        if (currentIndex < fOperationStartIndex || currentIndex >= fOperationEndExclusive) {
            return;
        }
        callback();
    }

    SkCanvas* fTarget;
    int fOperationStartIndex;
    int fOperationEndExclusive;
    int fCurrentOperationIndex = 0;
};

} // namespace

class KotlinAbortCallback: public SkPicture::AbortCallback {
public:
    KotlinAbortCallback(KInteropPointer data) : callback(data) {}
    bool abort() override {
        return static_cast<bool>(callback());
    }
private:
    KBooleanCallback callback;
};

SKIKO_EXPORT KNativePointer org_jetbrains_skia_Picture__1nMakeFromData
  (KNativePointer dataPtr) {
    SkData* data = reinterpret_cast<SkData*>((dataPtr));
    SkPicture* instance = SkPicture::MakeFromData(data).release();
    return reinterpret_cast<KNativePointer>(instance);
}

SKIKO_EXPORT void org_jetbrains_skia_Picture__1nPlayback
  (KNativePointer ptr, KNativePointer canvasPtr, KInteropPointer abort) {
    SkPicture* instance = reinterpret_cast<SkPicture*>((ptr));
    SkCanvas* canvas = reinterpret_cast<SkCanvas*>((canvasPtr));
    if (abort) {
        KotlinAbortCallback abortCallback(abort);
        instance->playback(canvas, &abortCallback);
    } else {
        instance->playback(canvas, nullptr);
    }
}

SKIKO_EXPORT void org_jetbrains_skia_Picture__1nGetCullRect
  (KNativePointer ptr, KInteropPointer ltrbArray) {
    SkPicture* instance = reinterpret_cast<SkPicture*>((ptr));
    SkRect cullRect = instance->cullRect();
    float* ltrb = reinterpret_cast<float*>(ltrbArray);
    ltrb[0] = cullRect.left();
    ltrb[1] = cullRect.top();
    ltrb[2] = cullRect.right();
    ltrb[3] = cullRect.bottom();
}

SKIKO_EXPORT KInt org_jetbrains_skia_Picture__1nGetUniqueId
  (KNativePointer ptr) {
    SkPicture* instance = reinterpret_cast<SkPicture*>((ptr));
    return instance->uniqueID();
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_Picture__1nSerializeToData
  (KNativePointer ptr) {
    SkPicture* instance = reinterpret_cast<SkPicture*>((ptr));
    SkData* data = instance->serialize().release();
    return reinterpret_cast<KNativePointer>(data);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_Picture__1nMakePlaceholder
  (KFloat left, KFloat top, KFloat right, KFloat bottom) {
    SkRect cull = SkRect::MakeLTRB(left, top, right, bottom);
    SkPicture* instance = SkPicture::MakePlaceholder(cull).release();
    return reinterpret_cast<KNativePointer>(instance);
}

SKIKO_EXPORT KInt org_jetbrains_skia_Picture__1nGetApproximateOpCount
  (KNativePointer ptr) {
    SkPicture* instance = reinterpret_cast<SkPicture*>((ptr));
    return instance->approximateOpCount();
}

SKIKO_EXPORT KInt org_jetbrains_skia_Picture__1nGetApproximateBytesUsed
  (KNativePointer ptr) {
    SkPicture* instance = reinterpret_cast<SkPicture*>((ptr));
    return static_cast<KInt>(instance->approximateBytesUsed());
}


SKIKO_EXPORT KNativePointer org_jetbrains_skia_Picture__1nMakeShader
  (KNativePointer ptr, KInt tmxValue, KInt tmyValue, KInt filterModeValue, KFloat* localMatrixArr, KBoolean hasTile, KFloat tileLeft, KFloat tileTop, KFloat tileRight, KFloat tileBottom) {
    SkPicture* instance = reinterpret_cast<SkPicture*>((ptr));
    SkTileMode tmx = static_cast<SkTileMode>(tmxValue);
    SkTileMode tmy = static_cast<SkTileMode>(tmyValue);
    SkFilterMode filterMode = static_cast<SkFilterMode>(filterModeValue);
    std::unique_ptr<SkMatrix> localMatrix = skMatrix(localMatrixArr);
    SkShader* shader;
    if (hasTile) {
        SkRect tileRect = SkRect::MakeLTRB(tileLeft, tileRight, tileBottom, tileTop);
        shader = instance->makeShader(tmx, tmy, filterMode, localMatrix.get(), &tileRect).release();
    } else {
        shader = instance->makeShader(tmx, tmy, filterMode, localMatrix.get(), nullptr).release();
    }
    return reinterpret_cast<KNativePointer>(shader);
}

SKIKO_EXPORT KNativePointer org_jetbrains_skia_Picture__1nMakeChunkPicture
  (KNativePointer ptr, KInt operationStartIndex, KInt operationEndExclusive) {
    SkPicture* instance = reinterpret_cast<SkPicture*>((ptr));
    if (operationStartIndex < 0 || operationEndExclusive <= operationStartIndex) {
        return nullptr;
    }

    SkPictureRecorder recorder;
    SkCanvas* recordingCanvas = recorder.beginRecording(instance->cullRect());
    ChunkRecordingCanvas chunkCanvas(recordingCanvas, operationStartIndex, operationEndExclusive);
    instance->playback(&chunkCanvas, nullptr);
    chunkCanvas.finish();
    return reinterpret_cast<KNativePointer>(recorder.finishRecordingAsPicture().release());
}

