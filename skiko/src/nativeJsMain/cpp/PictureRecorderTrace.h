#ifndef SKIKO_PICTURE_RECORDER_TRACE_H
#define SKIKO_PICTURE_RECORDER_TRACE_H

class SkCanvas;
class SkPicture;

void skikoRecordPictureDraw(SkCanvas* canvas, const SkPicture* picture);
bool skikoIsOperationTracingCanvas(SkCanvas* canvas);

#endif