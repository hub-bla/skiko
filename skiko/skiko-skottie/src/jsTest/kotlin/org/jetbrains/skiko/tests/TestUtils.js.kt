package org.jetbrains.skiko.tests

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.jetbrains.skiko.wasm.awaitSkiko

actual typealias SkipJsTarget = kotlin.test.Ignore

actual annotation class SkipWasmTarget

/**
 * Runs the [block] in a coroutine.
 */
actual fun runTest(block: suspend () -> Unit): TestReturnType = MainScope().promise {
    awaitSkiko.await<Any>()
    block()
}
