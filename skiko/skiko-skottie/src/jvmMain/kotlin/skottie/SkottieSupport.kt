package org.jetbrains.skia.skottie
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skiko.hostId
import org.jetbrains.skiko.LibraryLoader
import org.jetbrains.skiko.LockFile
import org.jetbrains.skiko.Library

fun loadNativeLibrary(name: String) = LibraryLoader(
    name = name,
    lockFile = LockFile.skiko,
).loadOnce()


actual object SkottieLibrary {
    actual fun load() {
        Library.load()
        loadNativeLibrary("skiko-skottie-$hostId")
        _nAfterLoad()
    }
}

private external fun _nAfterLoad()