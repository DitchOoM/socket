package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicTlsConfig
import org.junit.Assume.assumeTrue

/**
 * JVM subclass of [Http3LoopbackTestSuite]. Resolves the test cert/key from the jvmTest classpath
 * (`certs/`), and converts the `UnsatisfiedLinkError` thrown by the lazy quiche FFM binding (on
 * hosts where `libquiche.so` isn't staged) into a JUnit assumption so the loopback tests skip
 * rather than fail. Mirrors `:socket-quic`'s `JvmQuicServerTestSuite`.
 */
class JvmHttp3LoopbackTest : Http3LoopbackTestSuite() {
    override val timeScale: Double get() = parseTimeScale(System.getenv("QUIC_TEST_TIME_SCALE"))

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

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
