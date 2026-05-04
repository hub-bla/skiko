@file:JsModule("./skiko.mjs")
package org.jetbrains.skiko.wasm

import kotlin.js.Promise

actual external val awaitSkiko: Promise<JsAny>
