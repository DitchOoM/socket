package com.ditchoom.socket

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
