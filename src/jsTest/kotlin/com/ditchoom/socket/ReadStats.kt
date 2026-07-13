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

// Node net.Socket goes to flowing mode once a 'data' listener is attached (our ServerSocket does on
// accept), so the OS receive buffer is always drained and our ServerSocket can't be a non-draining peer.
// Node's write path is covered by NodeWriteBackpressureTests (raw net, paused peer) instead.
actual fun nonDrainingPeerIsReliable(): Boolean = false
