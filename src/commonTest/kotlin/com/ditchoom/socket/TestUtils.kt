package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
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
