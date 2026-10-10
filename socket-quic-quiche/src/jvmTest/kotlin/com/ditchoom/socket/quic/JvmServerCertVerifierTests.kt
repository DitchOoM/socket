package com.ditchoom.socket.quic

import java.io.File

/** JVM member of [ServerCertVerifierTestSuite]; runs on whichever backend (JNI or FFM) the build selected. */
class JvmServerCertVerifierTests : ServerCertVerifierTestSuite() {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    override fun twoCertificateChainTlsConfig() =
        QuicTlsConfig(certChainPath = certPath("two-cert-chain.crt"), privKeyPath = certPath("cert.key"))

    override fun twoCertificateChainPem(): String = File(certPath("two-cert-chain.crt")).readText()

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmServerCertVerifierTests::class, block)
}
