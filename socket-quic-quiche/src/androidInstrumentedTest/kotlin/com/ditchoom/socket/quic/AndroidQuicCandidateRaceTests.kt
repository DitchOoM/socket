package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Android (quiche JNI) member of [QuicCandidateRaceTestSuite], on the device's own `UdpSocket` actual. */
@RunWith(AndroidJUnit4::class)
class AndroidQuicCandidateRaceTests : QuicCandidateRaceTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidQuicCandidateRaceTests::class, block)
}
