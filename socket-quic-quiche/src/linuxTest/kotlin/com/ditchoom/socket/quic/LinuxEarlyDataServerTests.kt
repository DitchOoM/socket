@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/** Linux K/Native member of [EarlyDataServerTestSuite]. */
class LinuxEarlyDataServerTests : EarlyDataServerTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override fun platformQuicheApi(): QuicheApi = CinteropQuicheApi

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        api: QuicheApi,
    ): SharedQuicheServer = buildLinuxQuicServer(binding, tlsConfig, options, api = api)
}
