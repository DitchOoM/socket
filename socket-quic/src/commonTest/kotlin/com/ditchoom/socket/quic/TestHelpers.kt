package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
