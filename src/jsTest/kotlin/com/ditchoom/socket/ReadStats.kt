package com.ditchoom.socket

import com.ditchoom.socket.NetworkCapabilities.FULL_SOCKET_ACCESS
import com.ditchoom.socket.NetworkCapabilities.WEBSOCKETS_ONLY
import com.ditchoom.socket.harness.HarnessConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.js.Date
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

actual fun supportsIPv6(): Boolean = false // JS/browser doesn't have direct socket access

actual fun currentTimeMillis(): Long = Date.now().toLong()

actual fun isRunningInSimulator(): Boolean = false

internal actual fun isWindowsJvm(): Boolean = false

internal actual fun harnessHost(): String = HarnessConfig.host
