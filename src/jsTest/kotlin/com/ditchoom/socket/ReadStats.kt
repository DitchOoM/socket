package com.ditchoom.socket

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
            if (networkCapabilities().transports.contains(TransportKind.TCP)) {
                throw e
            }
        }
    }

actual fun supportsIPv6(): Boolean = false // JS/browser doesn't have direct socket access

actual fun currentTimeMillis(): Long = Date.now().toLong()

actual fun isRunningInSimulator(): Boolean = false

internal actual fun isWindowsJvm(): Boolean = false

internal actual fun harnessHost(): String = HarnessConfig.host
