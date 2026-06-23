package org.jetbrains.skiko.sample.extensions

import platform.Foundation.NSBundle
import platform.Foundation.NSURL

private val KEXE_DIR: String = NSBundle.mainBundle.bundlePath
private const val RESOURCES_PATH = "src/commonMain/resources"

actual fun resourcePath(resourceId: String) = run {
    val filePath = "$KEXE_DIR/../../../../$RESOURCES_PATH/$resourceId"
    NSURL.URLWithString(filePath)?.standardizedURL?.absoluteString ?: filePath
}
