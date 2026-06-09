package org.jetbrains.skia.skottie

actual object SkottieLibrary {
    actual fun load() {
        check(isSkottieModuleLoaded()) {
            "Skottie side module was not loaded"
        }
    }
}
