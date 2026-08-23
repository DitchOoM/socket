package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [FailedProbeConnectionIdTestSuite].
 *
 * Apple links quiche as a static archive embedded into a cinterop klib, and this repo has already
 * been bitten once by a klib carrying a stale `.a`. The CID accounting this suite exercises is
 * quiche's own, so a JVM-only guard would report #447 fixed while Apple shipped a `libquiche.a`
 * whose behaviour nothing had checked.
 *
 * cinterop fixes the binding at compile time, so there is no missing-native skip path and
 * [wrapTestBody] stays the default pass-through — on Apple this always runs.
 */
class AppleFailedProbeConnectionIdTests : FailedProbeConnectionIdTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
    ): SharedQuicheServer = buildAppleQuicServer(binding, tlsConfig, options)
}
