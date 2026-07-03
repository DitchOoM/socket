@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.F_OK
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_DGRAM
import platform.posix.access
import platform.posix.close
import platform.posix.htonl
import platform.posix.htons
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket

/**
 * Kotlin/Native (linuxX64) malformed-packet fuzz test — the K/N member of the shared
 * [QuicMalformedPacketTestSuite]. Provides linuxX64 cert resolution and a POSIX `sendto` for the raw
 * datagrams. Native compiles quiche via cinterop, so there is no `UnsatisfiedLinkError` skip path.
 */
class LinuxQuicMalformedPacketTests : QuicMalformedPacketTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun sendRawDatagram(
        port: Int,
        bytes: ByteArray,
    ) {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "raw datagram socket() failed" }
        try {
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.convert()
                addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                addr.sin_port = htons(port.toUShort())
                // Empty datagrams are valid UDP; pin a 1-element backing array but send 0 bytes for those.
                val pinnedBytes = if (bytes.isEmpty()) ByteArray(1) else bytes
                pinnedBytes.usePinned { pinned ->
                    sendto(
                        fd,
                        pinned.addressOf(0),
                        bytes.size.convert(),
                        0,
                        addr.ptr.reinterpret<sockaddr>(),
                        sizeOf<sockaddr_in>().convert(),
                    )
                }
            }
        } finally {
            close(fd)
        }
    }
}
