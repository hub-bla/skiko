package org.jetbrains.skiko

import kotlinx.browser.window
import org.w3c.dom.HTMLElement

internal actual inline fun <R> maybeSynchronized(lock: Any, block: () -> R): R =
    block()

actual fun currentNanoTime(): Long = (window.performance.now() * 1_000_000).toLong()

@InternalSkikoApi
actual fun loadOpenGLLibrary() {
   // Nothing to do here
}

actual fun loadAngleLibrary() {
    // Nothing to do here
}

annotation class WebImport(val name : String)
