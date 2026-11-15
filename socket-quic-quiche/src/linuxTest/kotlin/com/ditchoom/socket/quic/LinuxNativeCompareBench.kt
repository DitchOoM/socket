@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

/** Linux member of [NativeCompareBench]; inert unless NATIVE_BENCH is set. */
class LinuxNativeCompareBench : NativeCompareBench() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override fun maxRssRaw(): Long {
        val file = fopen("/proc/self/status", "r") ?: return 0
        try {
            memScoped {
                val line = allocArray<ByteVar>(256)
                while (fgets(line, 256, file) != null) {
                    val text = line.toKString()
                    if (text.startsWith("VmHWM:")) return text.filter { it.isDigit() }.toLong()
                }
            }
        } finally {
            fclose(file)
        }
        return 0
    }
}
