package com.ditchoom.socket.quic

import java.io.File

/**
 * JVM member of [RetiredCidInFlightPacketTestSuite] — the lane the #445 patch was first
 * mutation-proven on (green patched, red against a quiche built without
 * `patchQuicheRetiredCidRecvIsDrop`, where `quiche_conn_recv` answers the released packet with -6).
 *
 * Runs on whichever backend the build selected, so the FFM lane exercises the fix through the
 * `java.lang.foreign` binding and the default lane through JNI.
 */
class JvmRetiredCidInFlightPacketTests : RetiredCidInFlightPacketTestSuite() {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override fun platformQuicheApi(): QuicheApi = loadQuicheApi()

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        api: QuicheApi,
    ): SharedQuicheServer = buildJvmQuicServer(binding, tlsConfig, options, api = api)

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmRetiredCidInFlightPacketTests::class, block)
}
