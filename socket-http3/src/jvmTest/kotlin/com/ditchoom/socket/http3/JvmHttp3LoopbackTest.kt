package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import org.junit.Assume.assumeTrue

/**
 * JVM subclass of [Http3LoopbackTestSuite]. Resolves the test cert/key from the jvmTest classpath
 * (`certs/`), and converts the `UnsatisfiedLinkError` thrown by the lazy quiche FFM binding (on
 * hosts where `libquiche.so` isn't staged) into a JUnit assumption so the loopback tests skip
 * rather than fail. Mirrors `:socket-quic`'s `JvmQuicServerTestSuite`.
 */
class JvmHttp3LoopbackTest : Http3LoopbackTestSuite() {
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

    // The one hand-written copy of :socket-testsuite's `skipOnMissingNativeLib`. It cannot be
    // shared: :socket-testsuite `api`s :socket-http3, so depending on it from here is a cycle, and
    // :socket-testkit — the shared ancestor that carries SkipReason — is published, so it must not
    // take a junit dependency to host the `assumeTrue` half. Everything except that half is shared.
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            recordSkip(SkipReason.NativeLibraryUnavailable(e.message ?: "UnsatisfiedLinkError with no message"))
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
