package com.ditchoom.socket

import com.ditchoom.socket.harness.HarnessConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Returns true if running in an iOS/tvOS/watchOS Simulator environment.
 * iOS Simulators in CI often have restricted network access to external hosts.
 */
expect fun isRunningInSimulator(): Boolean

/**
 * Platform-specific return type for test functions.
 * On JVM/K/N: Unit (required by K/N test framework).
 * On JS: Any (allows returning Promise for mocha async test tracking).
 */
expect class TestRunResult

/**
 * Runs a test with real-time timeout (no virtual time skipping).
 * Platform-specific: uses runBlocking on JVM/Native, GlobalScope.promise on JS.
 */
internal expect fun runTestNoTimeSkipping(
    count: Int = 1,
    timeout: Duration = 30.seconds,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult

/**
 * Skip the block if running in a simulator environment (e.g., iOS Simulator in CI).
 */
internal inline fun skipOnSimulator(block: () -> Unit) {
    if (!isRunningInSimulator()) {
        block()
    }
}

/**
 * Wait for a mutex to be unlocked with a timeout.
 * This prevents tests from hanging indefinitely if the unlock never happens.
 */
internal suspend fun Mutex.lockWithTimeout(
    timeout: Duration = 10.seconds,
    owner: Any? = null,
) {
    withTimeout(timeout) {
        lock(owner)
    }
}

// ──────────────────────────────────────────────────────────────────────
// Harness reachability (see test-harness/ and TESTING_STRATEGY.md §3a)
// ──────────────────────────────────────────────────────────────────────

/**
 * Host the local test harness is reachable on for the current platform.
 *
 * JVM / K-Native / Apple: returns `HarnessConfig.host` (typically `127.0.0.1`)
 * — harness and test share the runner.
 *
 * Browser (JS/wasmJs): same default. Browser targets do not exercise the
 * socket harness today (no raw-socket surface), so this value is consumed
 * only by potential future WebSocket-shape tests; switch the browser actual
 * to `window.location.hostname` if that ever changes.
 */
internal expect fun harnessHost(): String

/**
 * `true` when the harness TCP echo endpoint is reachable from this process.
 *
 * Probes `HarnessConfig.echoPort` on [harnessHost] with a 500 ms budget.
 * Used by harness-backed tests so the suite stays green when the local
 * stack isn't up (e.g. local dev without Docker, or a CI runner where
 * `harnessUp` no-op'd because the host isn't supported).
 *
 * Returns `false` immediately on browser/wasmJs (`WEBSOCKETS_ONLY`).
 */
internal suspend fun isHarnessAvailable(): Boolean {
    if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return false
    return try {
        ClientSocket.connect(
            port = HarnessConfig.echoPort,
            hostname = harnessHost(),
            timeout = 500.milliseconds,
        ) { /* immediate close — we just needed to know the listener is alive */ }
        true
    } catch (_: Throwable) {
        false
    }
}
