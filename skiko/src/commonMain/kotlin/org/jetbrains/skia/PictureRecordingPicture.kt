package org.jetbrains.skia

/**
 * A single explicit [Canvas.drawPicture] call observed while a [PictureRecorder] tracing canvas was active.
 */
data class PictureRecordingPicture(
    val pictureId: Int,
    val depth: Int
)