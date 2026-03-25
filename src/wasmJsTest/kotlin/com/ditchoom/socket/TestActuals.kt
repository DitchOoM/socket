@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.ditchoom.socket

import kotlinx.coroutines.test.runTest
import kotlin.time.Duration

actual class TestRunResult

internal actual fun runTestNoTimeSkipping(
    count: Int,
    timeout: Duration,
    block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
): TestRunResult {
    runTest(timeout = timeout) {
        try {
            block()
        } catch (_: UnsupportedOperationException) {
            // All socket operations throw UnsupportedOperationException on WASM
        }
    }
    return TestRunResult()
}

actual suspend fun readStats(
    port: Int,
    contains: String,
): List<String> = emptyList()

actual fun supportsIPv6(): Boolean = false

@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

actual fun currentTimeMillis(): Long = jsDateNow().toLong()

actual fun isRunningInSimulator(): Boolean = false
