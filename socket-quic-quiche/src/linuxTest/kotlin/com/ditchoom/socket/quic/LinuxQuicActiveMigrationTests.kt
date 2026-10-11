@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/**
 * Linux K/Native member of [QuicActiveMigrationTestSuite].
 *
 * Both ends are Linux: the client migrates through `UdpSocketChannelFactory`, and the server answers
 * the migrated path through the shared unconnected socket, sending each reply to quiche's
 * `send_info.to` ([ServerConnectionUdpChannel]).
 *
 * Distinct from [LinuxQuicMigrationLoopbackTests], which migrates to the `127.0.0.2` loopback alias — a
 * Linux-only address trick that proves a move across local *addresses*. This member exercises the
 * portable fresh-ephemeral-port path every target runs.
 *
 * cinterop fixes the quiche binding at compile time, so there is no `UnsatisfiedLinkError` skip path
 * and [wrapTestBody] stays the default pass-through — on Linux this always runs.
 */
class LinuxQuicActiveMigrationTests : QuicActiveMigrationTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
}
