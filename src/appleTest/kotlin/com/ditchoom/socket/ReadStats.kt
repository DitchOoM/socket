package com.ditchoom.socket

import platform.Foundation.NSProcessInfo
import kotlin.time.TimeSource

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
