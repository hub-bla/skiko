package org.jetbrains.skiko.sample.extensions

import platform.Foundation.NSBundle

actual fun resourcePath(resourceId: String): String =
    NSBundle.mainBundle.bundlePath + "/$resourceId"
