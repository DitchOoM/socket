package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * QUIC test runner with a 15-second wall-clock timeout.
 *
 * Replaces the pre-v2 `kotlinx.coroutines.runBlocking` wrapper, which
 * doesn't exist on Kotlin/JS (or WasmJs) — the test class is in
 * `commonTest` and is supposed to run on every target to assert
 * common-API behavioral parity, but the old `runBlocking` fell over at
 * compile time on JS/WasmJs.
 *
 * Uses [runTest] so the result type is [TestResult] — a typealias for
 * `Unit` on JVM / K/N and `Promise<Unit>` on JS / WasmJs, giving the
 * test framework the right shape on every platform. The body runs on
 * `Dispatchers.Default` (via `withContext`) so real I/O and real
 * timing work — the reactive-driver tests rely on cooperative yield
 * and actual channel pumping and don't want virtual-time
 * fast-forwarding to skip over `withTimeout` budgets.
 */
fun runQuicTest(block: suspend CoroutineScope.() -> Unit): TestResult =
    runTest(timeout = 30.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(15.seconds) { block() }
        }
    }

/**
 * Reactive replacement for `delay(N)` followed by an assertion: yields the dispatcher
 * until [predicate] holds, with [timeout] as a wall-clock backstop. Converges within
 * one scheduler tick once the awaited event fires — no fixed wait, no polling on
 * wall-clock time, no spinning the CPU.
 *
 * Use this anywhere a test was written like `delay(500) // let X propagate; assertEquals(…)`.
 * The [reason] is surfaced in the [TimeoutCancellationException] message when the
 * predicate never holds, making timeouts diagnose themselves.
 */
suspend fun awaitUntil(
    timeout: Duration,
    reason: String,
    predicate: () -> Boolean,
) {
    try {
        withTimeout(timeout) {
            while (!predicate()) yield()
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("awaitUntil($timeout) timed out: $reason")
    }
}

/**
 * True when the current runtime is a Kotlin/Native Apple target
 * (macosArm64, macosX64, iosArm64, iosSimulatorArm64, iosX64,
 * tvosArm64, tvosSimulatorArm64, tvosX64, watchosArm64,
 * watchosSimulatorArm64, watchosX64).
 *
 * Used by [QuicHarnessIntegrationTests] to skip the harness suite
 * on Apple K/N targets: the production [withQuicConnection] code
 * path is correct (`AppleQuicConnectStartupProbe.b_…verifyPeerTrue`
 * passes), but the SecTrust-based verify_block crashes Network.framework's
 * TLS evaluation in a way we couldn't isolate after 8 CI iterations in
 * PR #54 — sec_protocol_options_set_verify_block + a non-trivial verify
 * block + the test runner's stdout buffering interact badly.
 *
 * Apple K/N QUIC client coverage is provided by the smaller
 * AppleQuicConnectStartupProbe (which exercises withQuicConnection
 * against unreachable hosts and proves the startup path works
 * end-to-end without crashing). The harness suite is the only Apple
 * gap; tracked under the existing TODO.md "macOS harness coverage" item.
 */
internal expect fun isAppleKNative(): Boolean
