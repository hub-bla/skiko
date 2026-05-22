#include <iostream>
#include <jni.h>
#include "interop.hh"
#include "SkData.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkPictureRecorder.h"
#include "include/core/SkDrawable.h"
#include "SkPicture.h"
#include "SkShader.h"

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

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureKt_Picture_1nMakeFromData
  (JNIEnv* env, jclass jclass, jlong dataPtr) {
    SkData* data = reinterpret_cast<SkData*>(static_cast<uintptr_t>(dataPtr));
    SkPicture* instance = SkPicture::MakeFromData(data).release();
    return reinterpret_cast<jlong>(instance);
}

class JAbortCallback: public SkPicture::AbortCallback {
public:
    JAbortCallback(JNIEnv* env, jobject supplier) : callback(env, supplier) {}

    bool abort() override {
        bool res = static_cast<bool>(callback());
        if (callback.isExceptionThrown())
          return false;
        return res;
    }
private:
    JBooleanCallback callback;
};

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_PictureKt__1nPlayback
  (JNIEnv* env, jclass jclass, jlong ptr, jlong canvasPtr, jobject abort) {
    SkPicture* instance = reinterpret_cast<SkPicture*>(static_cast<uintptr_t>(ptr));
    SkCanvas* canvas = reinterpret_cast<SkCanvas*>(static_cast<uintptr_t>(canvasPtr));
    if (abort == nullptr) {
        instance->playback(canvas, nullptr);
    } else {
        JAbortCallback callback(env, abort);
        instance->playback(canvas, &callback);
    }
}

extern "C" JNIEXPORT void JNICALL Java_org_jetbrains_skia_PictureKt__1nGetCullRect
  (JNIEnv* env, jclass jclass, jlong ptr, jfloatArray ltrbArray) {
    SkPicture* instance = reinterpret_cast<SkPicture*>(static_cast<uintptr_t>(ptr));
    SkRect cullRect = instance->cullRect();
    env->SetFloatArrayRegion(ltrbArray, 0, 4, reinterpret_cast<const jfloat*>(cullRect.asScalars()));
}

extern "C" JNIEXPORT jint JNICALL Java_org_jetbrains_skia_PictureKt__1nGetUniqueId
  (JNIEnv* env, jclass jclass, jlong ptr) {
    SkPicture* instance = reinterpret_cast<SkPicture*>(static_cast<uintptr_t>(ptr));
    return instance->uniqueID();
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureKt__1nSerializeToData
  (JNIEnv* env, jclass jclass, jlong ptr) {
    SkPicture* instance = reinterpret_cast<SkPicture*>(static_cast<uintptr_t>(ptr));
    SkData* data = instance->serialize().release();
    return reinterpret_cast<jlong>(data);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureKt__1nMakePlaceholder
  (JNIEnv* env, jclass jclass, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    SkRect cull = SkRect::MakeLTRB(left, top, right, bottom);
    SkPicture* instance = SkPicture::MakePlaceholder(cull).release();
    return reinterpret_cast<jlong>(instance);
}

extern "C" JNIEXPORT jint JNICALL Java_org_jetbrains_skia_PictureKt__1nGetApproximateOpCount
  (JNIEnv* env, jclass jclass, jlong ptr) {
    SkPicture* instance = reinterpret_cast<SkPicture*>(static_cast<uintptr_t>(ptr));
    return instance->approximateOpCount();
}

extern "C" JNIEXPORT jint JNICALL Java_org_jetbrains_skia_PictureKt__1nGetApproximateBytesUsed
  (JNIEnv* env, jclass jclass, jlong ptr) {
    SkPicture* instance = reinterpret_cast<SkPicture*>(static_cast<uintptr_t>(ptr));
    return static_cast<jint>(instance->approximateBytesUsed());
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureKt__1nMakeShader
  (JNIEnv* env, jclass jclass, jlong ptr, jint tmxValue, jint tmyValue, jint filterModeValue, jfloatArray localMatrixArr, jboolean hasTile, jfloat tileLeft, jfloat tileTop, jfloat tileRight, jfloat tileBottom) {
    SkPicture* instance = reinterpret_cast<SkPicture*>(static_cast<uintptr_t>(ptr));
    SkTileMode tmx = static_cast<SkTileMode>(tmxValue);
    SkTileMode tmy = static_cast<SkTileMode>(tmyValue);
    SkFilterMode filterMode = static_cast<SkFilterMode>(filterModeValue);
    std::unique_ptr<SkMatrix> localMatrix = skMatrix(env, localMatrixArr);
    SkShader* shader;
    if (hasTile) {
        SkRect tileRect = SkRect::MakeLTRB(tileLeft, tileRight, tileBottom, tileTop);
        shader = instance->makeShader(tmx, tmy, filterMode, localMatrix.get(), &tileRect).release();
    } else {
        shader = instance->makeShader(tmx, tmy, filterMode, localMatrix.get(), nullptr).release();
    }
    return reinterpret_cast<jlong>(shader);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_jetbrains_skia_PictureKt__1nMakeChunkPicture
  (JNIEnv* env, jclass jclass, jlong ptr, jint operationStartIndex, jint operationEndExclusive) {
    SkPicture* instance = reinterpret_cast<SkPicture*>(static_cast<uintptr_t>(ptr));
    if (operationStartIndex < 0 || operationEndExclusive <= operationStartIndex) {
        return 0;
    }

    SkPictureRecorder recorder;
    SkCanvas* recordingCanvas = recorder.beginRecording(instance->cullRect());
    ChunkRecordingCanvas chunkCanvas(recordingCanvas, operationStartIndex, operationEndExclusive);
    instance->playback(&chunkCanvas, nullptr);
    chunkCanvas.finish();
    return reinterpret_cast<jlong>(recorder.finishRecordingAsPicture().release());
}