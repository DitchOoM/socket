package com.ditchoom.socket.quic

/**
 * Apple member of [ServerCertVerifierTestSuite]. On iOS its third test also exercises the SecTrust path:
 * with verifyPeer on and the default [AppleTrustSource.SystemTrustStore], the device trust store refuses
 * the self-signed chain before the verifier is asked.
 */
class AppleServerCertVerifierTests : ServerCertVerifierTestSuite() {
    override fun twoCertificateChainTlsConfig() =
        QuicTlsConfig(
            certChainPath = AppleTestCerts.requireGenerated("two-cert-chain.crt"),
            privKeyPath = AppleTestCerts.requireGenerated("cert.key"),
        )

    override fun twoCertificateChainPem(): String = AppleTestCerts.readText(AppleTestCerts.requireGenerated("two-cert-chain.crt"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = AppleTestCerts.skippingWhenSimulatorLacksFixtures(block)
}
