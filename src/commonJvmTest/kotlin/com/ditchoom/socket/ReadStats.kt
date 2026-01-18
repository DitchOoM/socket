package com.ditchoom.socket

import java.net.Inet6Address
import java.net.NetworkInterface

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
