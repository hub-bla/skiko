package org.jetbrains.skia

fun Bitmap.Companion.makeFromImage(image: Image, context: DirectContext): Bitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(image.imageInfo)
    return if (image.readPixels(context, bitmap)) bitmap else {
        bitmap.close()
        throw RuntimeException("Failed to readPixels from $image")
    }
}

