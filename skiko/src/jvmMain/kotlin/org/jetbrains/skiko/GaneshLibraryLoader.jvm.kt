package org.jetbrains.skiko

@InternalSkikoApi
actual object GaneshLibraryLoader {
    actual fun load() {
        try {
            val ganeshLibrary = Class.forName("org.jetbrains.skia.gpu.ganesh.GaneshLibrary")
                .getField("INSTANCE")
                .get(null)
            ganeshLibrary.javaClass.getMethod("load").invoke(ganeshLibrary)
        } catch (e: ClassNotFoundException) {
            throw RenderException(
                "Ganesh rendering APIs require the org.jetbrains.skiko:skiko-ganesh dependency on the classpath.",
                e
            )
        }
    }
}
