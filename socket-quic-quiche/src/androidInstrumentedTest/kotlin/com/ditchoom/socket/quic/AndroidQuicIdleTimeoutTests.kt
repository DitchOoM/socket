package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Android (quiche JNI) member of the shared [QuicIdleTimeoutTestSuite] — the Android counterpart of
 * [QuicIdleTimeoutTests] (JVM) / [LinuxQuicIdleTimeoutTests]. Replaces the former hand-copy. Inherits the
 * clean idle-timeout End and the keepalive-keeps-alive-past-idle tests (the latter wrapped in the shared
 * `withLiveQuicConnection`, a no-op on the quiche backend), with the suite's `.scaled` timeouts so a slow
 * device/emulator gets the same headroom as the other platforms.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicIdleTimeoutTests : QuicIdleTimeoutTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
