package com.ditchoom.socket.quic

import java.io.File

/** JVM member of [EarlyDataServerTestSuite]: JNI by default, FFM with `-PquicheJvmBackend=ffm`. */
class JvmEarlyDataServerTests : EarlyDataServerTestSuite() {
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

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmEarlyDataServerTests::class, block)
}
