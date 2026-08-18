@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/**
 * Linux K/Native member of [QuicActiveMigrationTestSuite].
 *
 * Linux wires a real `UdpSocketChannelFactory` (`WithQuicConnection.linux.kt:199`), so both suite
 * tests are expected to pass here. That is the point of adding this member alongside the Apple one:
 * a suite that is red everywhere proves nothing about the platform, only about the suite. Linux
 * green + Apple red localises the defect to the platform.
 *
 * Distinct from the pre-existing [LinuxQuicMigrationLoopbackTests], which migrates to the `127.0.0.2`
 * loopback alias — a Linux-only address trick. This member exercises the portable fresh-ephemeral-port
 * path that every target can run. The alias test is kept: it additionally proves migration across a
 * different local *address*, not just a different port.
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
