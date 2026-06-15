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
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket

/**
 * Apple (Network.framework) run of the shared [QuicMalformedPacketTestSuite] — common-API parity
 * (issue #112). Raw malformed datagrams go out via a plain POSIX `sendto` (independent of NW), the
 * same approach as the Linux member.
 */
class AppleQuicMalformedPacketTests : QuicMalformedPacketTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    /** Skip on `--standalone` Apple simulators (see [shouldSkipQuicHarnessOnSimulator]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }

    override suspend fun sendRawDatagram(
        port: Int,
        bytes: ByteArray,
    ) {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "raw datagram socket() failed" }
        try {
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_len = sizeOf<sockaddr_in>().convert() // BSD/Darwin: sockaddr length byte
                addr.sin_family = AF_INET.convert()
                // htonl/htons are macros on Darwin (not K/N symbols). All Apple targets are
                // little-endian, so store the network-byte-order (big-endian) values directly:
                // 127.0.0.1 → bytes 7f 00 00 01 → 0x0100007f; port → byte-swapped 16-bit.
                addr.sin_addr.s_addr = 0x0100007fu
                addr.sin_port = (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).toUShort()
                // Empty datagrams are valid UDP; pin a 1-element backing array but send 0 bytes.
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
