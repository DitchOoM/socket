package com.ditchoom.socket.quic

/** JVM member of [ClosedConnectionLateDatagramTestSuite] — quiche over NIO, JNI or FFM per `-PquicheJvmBackend`. */
class JvmClosedConnectionLateDatagramTests : ClosedConnectionLateDatagramTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        tuning: QuicheDriverTuning,
    ): SharedQuicheServer = buildJvmQuicServer(binding, tlsConfig, options, tuning)

    override suspend fun wrapTestBody(block: suspend () -> Unit) =
        skipOnMissingNativeLib(JvmClosedConnectionLateDatagramTests::class, block)
}
