package com.ditchoom.socket

import com.ditchoom.socket.harness.HarnessConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Inet6Address
import java.net.NetworkInterface
import kotlin.time.Duration

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
            if (networkCapabilities().transports.contains(TransportKind.TCP)) throw e
        }
    }

actual fun supportsIPv6(): Boolean =
    try {
        NetworkInterface.getNetworkInterfaces()?.asSequence()?.any { iface ->
            iface.inetAddresses?.asSequence()?.any { it is Inet6Address && !it.isLoopbackAddress } == true
        } == true
    } catch (e: Exception) {
        false
    }

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun isRunningInSimulator(): Boolean = false

internal actual fun isWindowsJvm(): Boolean = System.getProperty("os.name", "").lowercase().contains("windows")

internal actual fun harnessHost(): String = HarnessConfig.host
