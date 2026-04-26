package org.jetbrains.skia.skottie

actual object SkottieLibrary {
    actual fun load() {
        // Side-module loading is registered in skiko-skottie.mjs via wasm-ready hooks.
    }
}
