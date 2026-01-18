package com.ditchoom.socket

import kotlinx.coroutines.asDeferred
import kotlin.js.Date
import kotlin.js.Promise

actual suspend fun readStats(
    port: Int,
    contains: String,
): List<String> {
    if (TcpPortUsed.check(port.toInt(), "127.0.0.1").asDeferred().await()) {
        return listOf("TCP CHECK FAIL PORT: $port")
    }
    return emptyList()
}

@JsModule("tcp-port-used")
@JsNonModule
external class TcpPortUsed {
    companion object {
        fun check(
            port: Int,
            address: String,
        ): Promise<Boolean>
    }
}

actual fun supportsIPv6(): Boolean = false // JS/browser doesn't have direct socket access

actual fun currentTimeMillis(): Long = Date.now().toLong()
