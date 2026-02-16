package com.ditchoom.socket

import com.ditchoom.socket.NetworkCapabilities.FULL_SOCKET_ACCESS
import com.ditchoom.socket.NetworkCapabilities.WEBSOCKETS_ONLY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asDeferred
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.time.Duration

actual typealias TestRunResult = Any

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal actual fun runTestNoTimeSkipping(
    count: Int,
    timeout: Duration,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult =
    GlobalScope.promise {
        try {
            withTimeout(timeout) {
                block()
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

actual fun isRunningInSimulator(): Boolean = false
