package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Android (quiche JNI) member of the shared [QuicDatagramTestSuite] — the Android counterpart of
 * [JvmQuicDatagramTests] / [LinuxQuicDatagramTests]. Replaces the former hand-copy, which only carried
 * `datagramRoundTrip` + `datagramsDisabledByDefault`; extending the suite ALSO gains the
 * `datagramTooLargeThrows` validation test the hand-copy was missing. The shared
 * `withLiveQuicConnection` retry on the round-trip is a no-op on the quiche backend (no NW drain storm).
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicDatagramTests : QuicDatagramTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidQuicDatagramTests::class, block)
}
