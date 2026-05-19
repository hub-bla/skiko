package org.jetbrains.skia.gpu.ganesh

import org.jetbrains.skiko.Library
import org.jetbrains.skiko.LibraryLoader
import org.jetbrains.skiko.LockFile
import org.jetbrains.skiko.hostId

fun loadNativeLibrary(name: String) = LibraryLoader(
    name = name,
    lockFile = LockFile.skiko,
).loadOnce()

actual object GaneshLibrary {
    actual fun load() {
        Library.load()
        loadNativeLibrary("skiko-ganesh-$hostId")
    }
}
