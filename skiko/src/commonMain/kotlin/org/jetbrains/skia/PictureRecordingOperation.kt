package org.jetbrains.skia

/**
 * A single drawing operation observed while a [PictureRecorder] recording canvas was active.
 */
data class PictureRecordingOperation(
    val kind: PictureRecordingOperationKind,
    val depth: Int
)

/**
 * High-level kinds of drawing/state operations that may be observed during picture recording.
 */
enum class PictureRecordingOperationKind {
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
    DRAW_EDGE_AA_QUAD
}