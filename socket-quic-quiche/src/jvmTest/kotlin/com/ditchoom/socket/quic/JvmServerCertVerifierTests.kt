package com.ditchoom.socket.quic

import java.io.File

/** JVM member of [ServerCertVerifierTestSuite]; runs on whichever backend (JNI or FFM) the build selected. */
class JvmServerCertVerifierTests : ServerCertVerifierTestSuite() {
    override suspend fun connectOneCandidate(
        endpoint: QuicEndpoint,
        serverName: String,
        options: QuicOptions,
        transport: com.ditchoom.socket.TransportConfig,
        timeout: kotlin.time.Duration,
    ): QuicScope = QuicheEngine.connect(QuicClientBinding.OwnSocket, endpoint, serverName, options, transport, timeout)

    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    override fun twoCertificateChainTlsConfig() =
        QuicTlsConfig(certChainPath = certPath("two-cert-chain.crt"), privKeyPath = certPath("cert.key"))

    override fun twoCertificateChainPem(): String = File(certPath("two-cert-chain.crt")).readText()

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmServerCertVerifierTests::class, block)
}
