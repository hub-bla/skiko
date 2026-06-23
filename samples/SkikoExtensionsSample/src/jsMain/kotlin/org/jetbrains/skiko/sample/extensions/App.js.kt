package org.jetbrains.skiko.sample.extensions

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.wasm.onWasmReady
import org.w3c.dom.HTMLCanvasElement

fun main() {
    window.addEventListener("DOMContentLoaded", {
        onWasmReady(::runApp)
    })
}

internal fun runApp() {
    val canvas = document.getElementById("SkikoTarget") as HTMLCanvasElement
    canvas.setAttribute("tabindex", "0")
    val skiaLayer = SkiaLayer()
    skiaLayer.attachTo(canvas)
    MainScope().launch {
        configureSkottieLayer(skiaLayer)
        skiaLayer.needRender()
        canvas.setAttribute("data-skiko-ready", "true")
    }
}
