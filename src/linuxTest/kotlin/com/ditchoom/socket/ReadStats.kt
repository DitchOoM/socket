package com.ditchoom.socket

import kotlin.time.TimeSource

actual suspend fun readStats(
    port: Int,
    contains: String,
): List<String> {
    // Linux implementation using ss or netstat could be added here
    // For now, return empty list
    return emptyList()
}

actual fun supportsIPv6(): Boolean = true // Linux supports IPv6

private val startMark = TimeSource.Monotonic.markNow()
actual fun currentTimeMillis(): Long = startMark.elapsedNow().inWholeMilliseconds
