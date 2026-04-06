package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue

class JvmQuicServerTestSuite : QuicServerTestSuite() {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name")
            ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(
        certChainPath = certPath("cert.crt"),
        privKeyPath = certPath("cert.key"),
    )

    override fun serverEngine(): QuicServerEngine =
        try {
            defaultQuicServerEngine()
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }

    override fun clientEngine(): QuicEngine =
        try {
            defaultQuicEngine()
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }
}
