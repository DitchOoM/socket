@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/** Kotlin/Native (linuxX64) large-payload integration test — the K/N member of [QuicLargePayloadTestSuite]. */
class LinuxQuicLargePayloadTests : QuicLargePayloadTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
}
