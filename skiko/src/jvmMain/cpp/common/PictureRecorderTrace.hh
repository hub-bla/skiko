#ifndef SKIKO_PICTURE_RECORDER_TRACE_HH
#define SKIKO_PICTURE_RECORDER_TRACE_HH

class SkCanvas;
class SkPicture;

void skikoRecordPictureDraw(SkCanvas* canvas, const SkPicture* picture);
bool skikoIsOperationTracingCanvas(SkCanvas* canvas);

#endif