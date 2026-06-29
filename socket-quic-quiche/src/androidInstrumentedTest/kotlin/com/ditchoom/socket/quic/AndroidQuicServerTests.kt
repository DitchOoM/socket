package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

/**
 * Android (quiche JNI) run of the shared [QuicServerTestSuite] — the Android counterpart of
 * [JvmQuicServerTestSuite] / [LinuxQuicServerTests] / [AppleQuicServerTests]. Until now Android
 * hand-reimplemented a handful of the server-suite tests in [AndroidQuicLoopbackTests] (issue #72);
 * extending the shared suite instead brings Android to **full parity** (all 12 server tests) and
 * removes the divergence risk of maintaining a parallel copy.
 *
 * Subclassing works the same way it does on JVM: `socket-testsuite`'s android variant pulls
 * `kotlin("test-junit")`, so the suite's `kotlin.test.@Test` methods carry `org.junit.@Test`, and
 * [AndroidJUnit4] discovers the inherited methods on this concrete subclass in the test APK.
 *
 * Android always uses the quiche JNI backend (like JVM/Linux), so — unlike [AppleQuicServerTests] —
 * it does **not** override [connectionCloseWithErrorIsObservedByPeer]: the JNI binding's
 * `quiche_conn_peer_error` surfaces the peer's CONNECTION_CLOSE application code, so the inherited
 * test exercises exactly the regression the old `peerConnectionCloseCodeIsObservedOverJni` guarded.
 *
 * The quiche TLS layer loads the cert chain + key from a **filesystem path**, so [AndroidTestCerts]
 * extracts the bundled PEM fixtures from the test APK to the instrumentation cache dir first.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicServerTests : QuicServerTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override fun localhostTlsConfig() =
        QuicTlsConfig(
            certChainPath = AndroidTestCerts.path("localhost.crt"),
            privKeyPath = AndroidTestCerts.path("localhost.key"),
        )

    override fun localhostCertPem() = File(AndroidTestCerts.path("localhost.crt")).readText()

    override fun unrelatedCaPem() = File(AndroidTestCerts.path("cert.crt")).readText()

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
