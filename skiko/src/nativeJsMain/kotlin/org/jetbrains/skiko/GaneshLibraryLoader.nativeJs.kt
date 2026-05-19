package org.jetbrains.skiko

@InternalSkikoApi
actual object GaneshLibraryLoader {
    actual fun load() {
        // Native/JS/WASM are statically linked or loaded by the target runtime.
    }
}
