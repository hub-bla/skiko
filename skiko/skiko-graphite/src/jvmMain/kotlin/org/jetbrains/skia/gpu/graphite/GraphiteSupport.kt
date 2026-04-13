package org.jetbrains.skia.gpu.graphite
import org.jetbrains.skiko.hostId
import org.jetbrains.skiko.LibraryLoader
import org.jetbrains.skiko.LockFile
import org.jetbrains.skiko.Library

fun loadNativeLibrary(name: String, additionalFile: String? = null) = LibraryLoader(
        name = name,
        additionalFile = additionalFile,
        lockFile = LockFile.skiko,
    ).loadOnce()


object GraphiteLibrary {
    fun load() {
        Library.load()
        loadNativeLibrary("skiko-graphite-$hostId")
    }
}
