package com.ditchoom.socket

import com.ditchoom.socket.NetworkCapabilities.FULL_SOCKET_ACCESS
import com.ditchoom.socket.NetworkCapabilities.WEBSOCKETS_ONLY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSProcessInfo
import kotlin.time.Duration
import kotlin.time.TimeSource

actual typealias TestRunResult = Unit

internal actual fun runTestNoTimeSkipping(
    count: Int,
    timeout: Duration,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult =
    runBlocking {
        try {
            withTimeout(timeout) {
                withContext(Dispatchers.Default.limitedParallelism(count)) {
                    block()
                }
            }
        } catch (e: UnsupportedOperationException) {
            when (getNetworkCapabilities()) {
                FULL_SOCKET_ACCESS -> throw e
                WEBSOCKETS_ONLY -> {}
            }
        }
    }

actual suspend fun readStats(
    port: Int,
    contains: String,
): List<String> {
//    delay(100)
//    yield()
//    if (!PortHelper().isPortOpenWithActualPort(port.convert())) {
//        return listOf("$port is still open")
//    }
    return emptyList()
}

actual fun supportsIPv6(): Boolean = true // Apple platforms support IPv6

private val startMark = TimeSource.Monotonic.markNow()

actual fun currentTimeMillis(): Long = startMark.elapsedNow().inWholeMilliseconds

/**
 * Detects if running in iOS/tvOS/watchOS Simulator by checking environment variables.
 * Simulators set SIMULATOR_DEVICE_NAME when running.
 */
actual fun isRunningInSimulator(): Boolean {
    val env = NSProcessInfo.processInfo.environment
    return env["SIMULATOR_DEVICE_NAME"] != null || env["SIMULATOR_UDID"] != null
}
