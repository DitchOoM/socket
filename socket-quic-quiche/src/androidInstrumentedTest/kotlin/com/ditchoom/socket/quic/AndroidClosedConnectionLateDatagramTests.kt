package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** Android (quiche JNI) member of [ClosedConnectionLateDatagramTestSuite], on the device's own `UdpSocket` actual. */
@RunWith(AndroidJUnit4::class)
class AndroidClosedConnectionLateDatagramTests : ClosedConnectionLateDatagramTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        tuning: QuicheDriverTuning,
    ): SharedQuicheServer = buildJvmQuicServer(binding, tlsConfig, options, tuning)

    override suspend fun wrapTestBody(block: suspend () -> Unit) =
        skipOnMissingNativeLib(AndroidClosedConnectionLateDatagramTests::class, block)
}
