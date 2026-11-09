package com.ditchoom.socket.quic

/** Apple member of [ClosedConnectionLateDatagramTestSuite]: the POSIX UDP server under Kotlin/Native. */
class AppleClosedConnectionLateDatagramTests : ClosedConnectionLateDatagramTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        tuning: QuicheDriverTuning,
    ): SharedQuicheServer = buildAppleQuicServer(binding, tlsConfig, options, tuning)
}
