package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.Redrawer

/**
 * Creates an instance of [Redrawer] using [renderApi].
 * Valid values for [renderApi] are: [GraphicsApi.OPENGL], [GraphicsApi.METAL].
 * If [renderApi] is not one of the valid, then throws IllegalArgumentException.
 */
internal fun createNativeRedrawer(
    layer: SkiaLayer,
    renderApi: GraphicsApi
): Redrawer = throw UnsupportedOperationException(
    "Native $renderApi rendering is provided by the skiko-ganesh extension module"
)
