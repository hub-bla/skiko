package org.jetbrains.skia

import org.jetbrains.skia.impl.*
import org.jetbrains.skia.impl.Library.Companion.staticLoad

class PictureRecorder internal constructor(ptr: NativePointer) : Managed(ptr, _FinalizerHolder.PTR) {
    companion object {
        // TODO
        /**
         *
         * Signal that the caller is done recording. This invalidates the canvas returned by
         * [.beginRecording]/[.getRecordingCanvas].
         *
         *
         * Unlike [.finishRecordingAsPicture], which returns an immutable picture,
         * the returned drawable may contain live references to other drawables (if they were added to
         * the recording canvas) and therefore this drawable will reflect the current state of those
         * nested drawables anytime it is drawn or a new picture is snapped from it (by calling
         * [Drawable.makePictureSnapshot]).
         */
        // public Drawable finishRecordingAsPicture(@NotNull Rect cull) {
        //     Stats.onNativeCall();
        //     return new Drawable(_nFinishRecordingAsDrawable(_ptr, 0));
        // }

        init {
            staticLoad()
        }
    }

    constructor() : this(PictureRecorder_nMake()) {
        Stats.onNativeCall()
    }

    private object _FinalizerHolder {
        val PTR = PictureRecorder_nGetFinalizer()
    }

    /**
     * Returns the canvas that records the drawing commands.
     *
     * @param bounds the cull rect used when recording this picture. Any drawing the falls outside
     * of this rect is undefined, and may be drawn or it may not.
     * @param bbh optional acceleration structure
     * @return the canvas.
     */
    fun beginRecording(bounds: Rect, bbh: BBHFactory? = null): Canvas {
        return beginRecording(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            bbh
        )
    }

    /**
     * Returns the canvas that records the drawing commands.
     *
     * @param left   the left side of the cull rect used when recording this picture. Any drawing
     *               that falls outside of this rect is undefined and may be drawn, or it may not.
     * @param top    the top side of the cull rect used when recording this picture. Any drawing
     *               that falls outside of this rect is undefined and may be drawn, or it may not.
     * @param right  the right side of the cull rect used when recording this picture. Any drawing
     *               that falls outside of this rect is undefined and may be drawn, or it may not.
     * @param bottom the bottom side of the cull rect used when recording this picture. Any drawing
     *               that falls outside of this rect is undefined and may be drawn, or it may not.
     * @param bbh    optional acceleration structure
     * @return the canvas.
     */
    fun beginRecording(left: Float, top: Float, right: Float, bottom: Float, bbh: BBHFactory? = null): Canvas {
        return try {
            Stats.onNativeCall()
            Canvas(
                _nBeginRecording(
                    _ptr,
                    left,
                    top,
                    right,
                    bottom,
                    getPtr(bbh)
                ), false, this
            )
        } finally {
            reachabilityBarrier(this)
        }
    }

    /**
     * Returns a recording canvas that also collects high-level operation kinds and save/restore depth.
     *
     * Recorded operations can be retrieved through [recordedOperations] after drawing has happened.
     */
    fun beginRecordingWithOperationTrace(bounds: Rect, bbh: BBHFactory? = null): Canvas {
        return beginRecordingWithOperationTrace(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            bbh
        )
    }

    /**
     * Returns a recording canvas that also collects high-level operation kinds and save/restore depth.
     *
     * Recorded operations can be retrieved through [recordedOperations] after drawing has happened.
     */
    fun beginRecordingWithOperationTrace(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        bbh: BBHFactory? = null
    ): Canvas {
        return try {
            Stats.onNativeCall()
            Canvas(
                _nBeginRecordingWithOperationTrace(
                    _ptr,
                    left,
                    top,
                    right,
                    bottom,
                    getPtr(bbh)
                ), false, this
            )
        } finally {
            reachabilityBarrier(this)
        }
    }

    /**
     * @return  the recording canvas if one is active, or null if recording is not active.
     */
    val recordingCanvas: Canvas?
        get() = try {
            Stats.onNativeCall()
            val ptr = _nGetRecordingCanvas(_ptr)
            if (ptr == NullPointer) null else Canvas(ptr, false, this)
        } finally {
            reachabilityBarrier(this)
        }

    /**
     * Returns operations collected by the most recent tracing recording session started with
     * [beginRecordingWithOperationTrace].
     */
    val recordedOperations: List<PictureRecordingOperation>
        get() = try {
            Stats.onNativeCall()
            val count = _nGetRecordedOperationCount(_ptr)
            if (count == 0) {
                emptyList()
            } else {
                val repr = withResult(IntArray(count * 2)) {
                    _nGetRecordedOperations(_ptr, it)
                }
                List(count) { index ->
                    val offset = index * 2
                    PictureRecordingOperation(
                        kind = PictureRecordingOperationKind.entries[repr[offset]],
                        depth = repr[offset + 1]
                    )
                }
            }
        } finally {
            reachabilityBarrier(this)
        }

    /**
     * Returns explicit [Canvas.drawPicture] calls collected by the most recent tracing recording
     * session started with [beginRecordingWithOperationTrace].
     */
    val recordedPictures: List<PictureRecordingPicture>
        get() = try {
            Stats.onNativeCall()
            val count = _nGetRecordedPictureCount(_ptr)
            if (count == 0) {
                emptyList()
            } else {
                val repr = withResult(IntArray(count * 2)) {
                    _nGetRecordedPictures(_ptr, it)
                }
                List(count) { index ->
                    val offset = index * 2
                    PictureRecordingPicture(
                        pictureId = repr[offset],
                        depth = repr[offset + 1]
                    )
                }
            }
        } finally {
            reachabilityBarrier(this)
        }

    /**
     * Returns drawable draws collected by the most recent tracing recording session started with
     * [beginRecordingWithOperationTrace].
     *
     * Each entry identifies the drawable kind and the range of [recordedOperations] emitted while
     * that drawable replayed into the recorder canvas.
     */
    val recordedDrawables: List<PictureRecordingDrawable>
        get() = try {
            Stats.onNativeCall()
            val count = _nGetRecordedDrawableCount(_ptr)
            if (count == 0) {
                emptyList()
            } else {
                val repr = withResult(IntArray(count * 5)) {
                    _nGetRecordedDrawables(_ptr, it)
                }
                List(count) { index ->
                    val offset = index * 5
                    val operationStart = repr[offset + 3]
                    val operationEndExclusive = repr[offset + 4]
                    PictureRecordingDrawable(
                        kind = PictureRecordingDrawableKind.entries[repr[offset]],
                        generationId = repr[offset + 1],
                        depth = repr[offset + 2],
                        operationIndexRange = if (operationStart >= operationEndExclusive) {
                            IntRange.EMPTY
                        } else {
                            operationStart until operationEndExclusive
                        }
                    )
                }
            }
        } finally {
            reachabilityBarrier(this)
        }

    val traceGraph: PictureRecordingTraceGraph
        get() = buildPictureRecordingTraceGraph(
            operations = recordedOperations,
            drawables = recordedDrawables
        )

    fun suggestChunkCandidates(targetChunkCount: Int): List<PictureRecordingChunkCandidate> =
        traceGraph.suggestChunkCandidates(recordedOperations, targetChunkCount)

    /**
     *
     * Signal that the caller is done recording. This invalidates the canvas returned by
     * [.beginRecording]/[.getRecordingCanvas].
     *
     *
     * The returned picture is immutable. If during recording drawables were added to the canvas,
     * these will have been "drawn" into a recording canvas, so that this resulting picture will
     * reflect their current state, but will not contain a live reference to the drawables
     * themselves.
     */
    fun finishRecordingAsPicture(): Picture {
        return try {
            Stats.onNativeCall()
            Picture(_nFinishRecordingAsPicture(_ptr))
        } finally {
            reachabilityBarrier(this)
        }
    }

    /**
     * Signal that the caller is done recording, and update the cull rect to use for bounding
     * box hierarchy (BBH) generation. The behavior is the same as calling
     * [.finishRecordingAsPicture], except that this method updates the cull rect
     * initially passed into [.beginRecording].
     *
     * @param cull the new culling rectangle to use as the overall bound for BBH generation
     * and subsequent culling operations.
     * @return the picture containing the recorded content.
     */
    fun finishRecordingAsPicture(cull: Rect): Picture {
        return finishRecordingAsPicture(
            cull.left,
            cull.top,
            cull.right,
            cull.bottom
        )
    }

    /**
     * Finalizes the recording of the drawing commands and creates an immutable picture object
     * that encapsulates the recorded content. The cull rect provided defines the boundaries
     * for the recorded content and can be used for bounding box hierarchy (BBH) generation
     * and subsequent culling operations. After this call, the canvas returned by any
     * `beginRecording` or `getRecordingCanvas` method becomes invalid.
     *
     * @param cullLeft The left side of the cull rect defining the visible bounds of the recording.
     *                 Any drawing outside this boundary may or may not be included in the result.
     * @param cullTop The top side of the cull rect defining the visible bounds of the recording.
     *                Any drawing outside this boundary may or may not be included in the result.
     * @param cullRight The right side of the cull rect defining the visible bounds of the recording.
     *                  Any drawing outside this boundary may or may not be included in the result.
     * @param cullBottom The bottom side of the cull rect defining the visible bounds of the recording.
     *                   Any drawing outside this boundary may or may not be included in the result.
     * @return An immutable [Picture] object that contains the recorded drawing commands.
     */
    fun finishRecordingAsPicture(cullLeft: Float, cullTop: Float, cullRight: Float, cullBottom: Float): Picture {
        return try {
            Stats.onNativeCall()
            Picture(
                _nFinishRecordingAsPictureWithCull(
                    _ptr,
                    cullLeft,
                    cullTop,
                    cullRight,
                    cullBottom
                )
            )
        } finally {
            reachabilityBarrier(this)
        }
    }
}


@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nMake")
private external fun PictureRecorder_nMake(): NativePointer

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nGetFinalizer")
private external fun PictureRecorder_nGetFinalizer(): NativePointer

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nBeginRecording")
private external fun _nBeginRecording(
    ptr: NativePointer,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    bbh: NativePointer
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nBeginRecordingWithOperationTrace")
private external fun _nBeginRecordingWithOperationTrace(
    ptr: NativePointer,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    bbh: NativePointer
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nGetRecordingCanvas")
private external fun _nGetRecordingCanvas(ptr: NativePointer): NativePointer

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nGetRecordedOperationCount")
private external fun _nGetRecordedOperationCount(ptr: NativePointer): Int

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nGetRecordedOperations")
private external fun _nGetRecordedOperations(ptr: NativePointer, operations: InteropPointer)

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nGetRecordedPictureCount")
private external fun _nGetRecordedPictureCount(ptr: NativePointer): Int

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nGetRecordedPictures")
private external fun _nGetRecordedPictures(ptr: NativePointer, pictures: InteropPointer)

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nGetRecordedDrawableCount")
private external fun _nGetRecordedDrawableCount(ptr: NativePointer): Int

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nGetRecordedDrawables")
private external fun _nGetRecordedDrawables(ptr: NativePointer, drawables: InteropPointer)

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nFinishRecordingAsPicture")
private external fun _nFinishRecordingAsPicture(ptr: NativePointer): NativePointer

@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nFinishRecordingAsPictureWithCull")
private external fun _nFinishRecordingAsPictureWithCull(
    ptr: NativePointer,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
): NativePointer


@ExternalSymbolName("org_jetbrains_skia_PictureRecorder__1nFinishRecordingAsDrawable")
private external fun _nFinishRecordingAsDrawable(ptr: NativePointer): NativePointer

