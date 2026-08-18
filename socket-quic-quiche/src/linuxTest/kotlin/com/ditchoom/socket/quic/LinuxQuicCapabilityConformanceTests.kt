@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/**
 * Linux K/Native member of [QuicCapabilityConformanceTestSuite].
 *
 * Linux declares [LocalEndpointSupport.Bindable] (`WithQuicConnection.linux.kt`) on the strength of the
 * io_uring `UdpSocket` actual binding before it connects. Together with [JvmQuicCapabilityConformanceTests]
 * this is the control group for the Apple member: a conformance suite red on every platform indicts the
 * suite, whereas JVM+Linux green beside a red Apple localises the defect to the platform.
 *
 * cinterop fixes the quiche binding at compile time, so there is no `UnsatisfiedLinkError` skip path and
 * [wrapTestBody] stays the default pass-through — on Linux this always runs.
 */
class LinuxQuicCapabilityConformanceTests : QuicCapabilityConformanceTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
}
