package com.ditchoom.socket

import com.ditchoom.socket.NetworkCapabilities.FULL_SOCKET_ACCESS
import com.ditchoom.socket.NetworkCapabilities.WEBSOCKETS_ONLY
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
    try {
        val process =
            ProcessBuilder()
                .command("lsof", "-iTCP:$port", "-sTCP:$contains", "-l", "-n")
                .redirectErrorStream(true)
                .start()
        try {
            process.inputStream.use { stream ->
                return String(stream.readBytes())
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .filter { it.contains(contains) }
            }
        } finally {
            process.destroy()
        }
    } catch (t: Throwable) {
        return emptyList()
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
