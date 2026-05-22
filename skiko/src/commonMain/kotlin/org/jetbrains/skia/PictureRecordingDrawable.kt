package org.jetbrains.skia

/**
 * A drawable observed while a [PictureRecorder] tracing canvas was active.
 *
 * [operationIndexRange] points to the slice inside [PictureRecorder.recordedOperations] that was
 * emitted while this drawable replayed into the recorder canvas.
 */
data class PictureRecordingDrawable(
    val kind: PictureRecordingDrawableKind,
    val generationId: Int,
    val depth: Int,
    val operationIndexRange: IntRange
)

enum class PictureRecordingDrawableKind {
    UNKNOWN,
    RENDER_NODE
}