@file:JsModule("./skiko-skottie.mjs")
@file:JsNonModule
package org.jetbrains.skia.skottie

import kotlin.js.Promise

@JsName("loadSkikoExtension")
internal external fun loadSkikoExtension(url: String): Promise<Unit>

@JsName("skottieSetupRegistered")
internal external val skottieSetupRegistered: Boolean
