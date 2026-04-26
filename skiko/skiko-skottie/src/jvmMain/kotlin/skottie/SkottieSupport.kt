package org.jetbrains.skia.skottie
import org.jetbrains.skiko.hostId
import org.jetbrains.skiko.LibraryLoader
import org.jetbrains.skiko.LockFile
import org.jetbrains.skiko.Library

private val skottieLoader = LibraryLoader(
    name = "skiko-skottie-$hostId",
    lockFile = LockFile.skiko,
    init = {
        try {
            _nAfterLoad()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
)

actual object SkottieLibrary {
    actual fun load() {
        Library.load()
        skottieLoader.loadOnce()
    }
}

private external fun _nAfterLoad()