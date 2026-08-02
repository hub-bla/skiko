package org.jetbrains.skia.gpu.graphite

import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.use
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs

@OptIn(ExperimentalSkikoApi::class)
internal actual fun makeTestGraphiteContext(): GraphiteContext? = when (hostOs) {
    OS.MacOS -> makeTestMetalContext()
    OS.Windows -> GraphiteContext.makeDawnD3D()
    else -> null
}

@OptIn(ExperimentalSkikoApi::class)
private fun makeTestMetalContext(): GraphiteContext {
    GraphiteLibrary.load()
    val metalObjects = _nCreateMetalObjects()
    check(metalObjects.size == 2) { "Failed to create test Metal objects" }
    return try {
        GraphiteContext.makeMetal(metalObjects[0], metalObjects[1])
    } finally {
        _nReleaseMetalObjects(metalObjects[0], metalObjects[1])
    }
}

@OptIn(ExperimentalSkikoApi::class)
internal actual fun useTestExternalBackendTexture(
    block: (GraphiteContext, BackendTexture) -> Unit,
): Boolean {
    val (context, texturePtr) = when (hostOs) {
        OS.MacOS -> makeTestMetalContext() to _nCreateMetalTexture()
        OS.Windows -> {
            val objects = _nCreateDawnTestObjects()
            if (objects.size != 2) return false
            GraphiteContext(objects[0]) to objects[1]
        }
        else -> return false
    }
    if (texturePtr == 0L) {
        context.close()
        return false
    }

    return try {
        context.use {
            val backendTexture = when (hostOs) {
                OS.MacOS -> BackendTexture.makeMetal(8, 8, texturePtr)
                OS.Windows -> BackendTexture.makeDawn(WGPUTexture(texturePtr))
                else -> error("unreachable")
            }
            backendTexture.use { block(context, backendTexture) }
        }
        true
    } finally {
        when (hostOs) {
            OS.MacOS -> _nReleaseMetalTexture(texturePtr)
            OS.Windows -> _nReleaseDawnTexture(texturePtr)
            else -> Unit
        }
    }
}

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteTestHelpers__1nCreateMetalObjects")
private external fun _nCreateMetalObjects(): LongArray

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteTestHelpers__1nReleaseMetalObjects")
private external fun _nReleaseMetalObjects(devicePtr: NativePointer, queuePtr: NativePointer)

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteTestHelpers__1nCreateMetalTexture")
private external fun _nCreateMetalTexture(): NativePointer

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteTestHelpers__1nReleaseMetalTexture")
private external fun _nReleaseMetalTexture(texturePtr: NativePointer)

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteTestHelpers__1nCreateDawnTestObjects")
private external fun _nCreateDawnTestObjects(): LongArray

@ExternalSymbolName("org_jetbrains_skia_gpu_graphite_GraphiteTestHelpers__1nReleaseDawnTexture")
private external fun _nReleaseDawnTexture(texturePtr: NativePointer)
