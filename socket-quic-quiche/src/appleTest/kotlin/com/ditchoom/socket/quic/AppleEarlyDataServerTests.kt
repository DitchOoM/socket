package com.ditchoom.socket.quic

/** Apple K/Native member of [EarlyDataServerTestSuite]. */
class AppleEarlyDataServerTests : SharedQuicheEarlyDataServerTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    override fun platformQuicheApi(): QuicheApi = CinteropQuicheApi

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        api: QuicheApi,
    ): SharedQuicheServer = buildAppleQuicServer(binding, tlsConfig, options, api = api)
}
