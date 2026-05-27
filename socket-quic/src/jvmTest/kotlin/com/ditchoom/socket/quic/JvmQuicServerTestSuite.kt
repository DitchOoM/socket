package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue

/**
 * JVM subclass of [QuicServerTestSuite].
 *
 * The block-taker factories delegate straight to the commonMain
 * [withQuicServerEngine] / [withQuicEngine] helpers. Native-lib absence
 * (`UnsatisfiedLinkError` from `loadQuicheApi`) is converted to a JUnit
 * assumption so the test is skipped instead of failing on machines where
 * the quiche JNI hasn't been built (typically non-Linux/non-macOS hosts).
 *
 * The previous `@AfterTest` engine-tracker retrofit is gone — scope-only
 * construction closes the leak by construction.
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

    override suspend fun <R> withServerEngine(block: suspend (QuicServerEngine) -> R): R =
        try {
            withQuicServerEngine(block)
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }

    override suspend fun <R> withClientEngine(block: suspend (QuicEngine) -> R): R =
        try {
            withQuicEngine(block)
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }
}
