@file:JsModule("./js-skiko-reexport-symbols.mjs")
@file:JsNonModule
@file:JsQualifier("api")
package org.jetbrains.skiko.wasm

import kotlin.js.Promise

actual external val awaitSkiko: Promise<JsAny>
