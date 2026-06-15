package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue

/**
 * JVM subclass of [QuicServerTestSuite].
 *
 * The base suite calls the top-level [withQuicServer] / [withQuicConnection]
 * helpers directly. Native-lib absence (`UnsatisfiedLinkError` from the
 * lazy `loadQuicheApi()` inside those helpers) is converted to a JUnit
 * assumption by [wrapTestBody] so the test is skipped instead of failing
 * on machines where the quiche JNI hasn't been built (typically non-Linux
 * / non-macOS hosts).
 */
class JvmQuicServerTestSuite : QuicServerTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
        )

    override fun localhostTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("localhost.crt"),
            privKeyPath = certPath("localhost.key"),
        )

    override fun localhostCertPem() = java.io.File(certPath("localhost.crt")).readText()

    override fun unrelatedCaPem() = java.io.File(certPath("cert.crt")).readText()

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
