package org.jetbrains.skia.skottie

actual object SkottieLibrary {
    init {
        // Force skiko-skottie.mjs import so hook registration always runs.
        skottieSetupRegistered
        // Force js-skiko-skottie-reexport-symbols.mjs import so generated symbol reexports are published.
        skottieReexportAnchor
    }

    actual fun load() {}
}
