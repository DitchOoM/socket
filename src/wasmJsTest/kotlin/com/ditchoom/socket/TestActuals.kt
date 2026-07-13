@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.ditchoom.socket

import com.ditchoom.socket.harness.HarnessConfig
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

actual fun supportsIPv6(): Boolean = false

@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

actual fun currentTimeMillis(): Long = jsDateNow().toLong()

actual fun isRunningInSimulator(): Boolean = false

internal actual fun isWindowsJvm(): Boolean = false

internal actual fun harnessHost(): String = HarnessConfig.host

// Browser/Wasm has no socket access; the write-timeout harness does not apply.
actual fun nonDrainingPeerIsReliable(): Boolean = false
