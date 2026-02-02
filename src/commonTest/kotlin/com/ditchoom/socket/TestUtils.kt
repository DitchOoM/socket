@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.NetworkCapabilities.FULL_SOCKET_ACCESS
import com.ditchoom.socket.NetworkCapabilities.WEBSOCKETS_ONLY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Returns true if running in an iOS/tvOS/watchOS Simulator environment.
 * iOS Simulators in CI often have restricted network access to external hosts.
 */
expect fun isRunningInSimulator(): Boolean

internal fun runTestNoTimeSkipping(
    count: Int = 1,
    timeout: Duration = 30.seconds,
    block: suspend TestScope.() -> Unit,
) = runTest(timeout = timeout) {
    try {
        withContext(Dispatchers.Default.limitedParallelism(count)) {
            block()
        }
    } catch (e: UnsupportedOperationException) {
        // ignore
        when (getNetworkCapabilities()) {
            FULL_SOCKET_ACCESS -> throw e
            WEBSOCKETS_ONLY -> {} // ignore, expected on browsers
        }
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
