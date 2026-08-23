@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/**
 * Linux K/Native member of [FailedProbeConnectionIdTestSuite].
 *
 * Linux is where the field failures were captured (the public echo server the device rig drives is a
 * Linux host), so this member checks the retirement on the same native the measurements came from.
 *
 * cinterop fixes the binding at compile time, so there is no missing-native skip path and
 * [wrapTestBody] stays the default pass-through — on Linux this always runs.
 */
class LinuxFailedProbeConnectionIdTests : FailedProbeConnectionIdTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
    ): SharedQuicheServer = buildLinuxQuicServer(binding, tlsConfig, options)
}
