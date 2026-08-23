package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [RetiredCidInFlightPacketTestSuite].
 *
 * The reason this member matters more than a "one more platform" box-tick: Apple links quiche as a
 * static archive embedded into a cinterop klib, and this repo has already been bitten once by a klib
 * carrying a **stale `.a`**. A JVM-only guard would report the #445 fix healthy while Apple shipped a
 * `libquiche.a` built before the patch, and nothing would have said so. This is the standing check
 * that the archive iOS and macOS actually link has the fix compiled in.
 *
 * cinterop fixes the binding at compile time, so there is no missing-native skip path and
 * [wrapTestBody] stays the default pass-through — on Apple this always runs.
 */
class AppleRetiredCidInFlightPacketTests : RetiredCidInFlightPacketTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    override fun platformQuicheApi(): QuicheApi = CinteropQuicheApi

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        api: QuicheApi,
    ): SharedQuicheServer = buildAppleQuicServer(binding, tlsConfig, options, api = api)
}
