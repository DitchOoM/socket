package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue

/**
 * JVM idle-timeout / keepalive test — the JVM member of the shared [QuicIdleTimeoutTestSuite]. Provides
 * JVM cert resolution and the `UnsatisfiedLinkError → assumeTrue` skip.
 */
class QuicIdleTimeoutTests : QuicIdleTimeoutTestSuite() {
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
