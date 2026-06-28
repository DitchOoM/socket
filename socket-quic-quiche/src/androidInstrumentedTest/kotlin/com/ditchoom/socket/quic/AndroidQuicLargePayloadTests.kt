package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Android (quiche JNI) member of the shared [QuicLargePayloadTestSuite] — the Android counterpart of the
 * JVM/Linux members. Replaces the former hand-copy. Inherits the 1 MB single-stream integrity transfer
 * and the 4×256 KB interleaved-streams transfer, both under the suite's flow-control window config, so
 * Android exercises the same large-payload / interleaving path as the other platforms.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicLargePayloadTests : QuicLargePayloadTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
