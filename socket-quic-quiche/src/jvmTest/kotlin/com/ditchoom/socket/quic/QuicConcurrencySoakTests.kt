package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue

/**
 * JVM concurrency + soak test — the JVM member of the shared [QuicConcurrencySoakTestSuite]. Provides
 * JVM cert resolution (classpath `certs/`) and the `UnsatisfiedLinkError → assumeTrue` skip. The test
 * bodies live in the common suite, guaranteeing parity with the Linux K/N port.
 */
class QuicConcurrencySoakTests : QuicConcurrencySoakTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
