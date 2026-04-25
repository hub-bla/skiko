package org.jetbrains.skia.gpu.graphite
import org.jetbrains.skiko.hostId
import org.jetbrains.skiko.LibraryLoader
import org.jetbrains.skiko.LockFile
import org.jetbrains.skiko.Library

fun loadNativeLibrary(name: String) = LibraryLoader(
        name = name,
        lockFile = LockFile.skiko,
    ).loadOnce()


actual object GraphiteLibrary {
    actual fun load() {
        Library.load()
        loadNativeLibrary("skiko-graphite-$hostId")
    }
}
