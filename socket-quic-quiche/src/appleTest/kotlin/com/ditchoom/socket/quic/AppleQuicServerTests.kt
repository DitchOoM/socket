package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicServerTestSuite] — the Apple counterpart of [JvmQuicServerTestSuite],
 * [LinuxQuicServerTests] and `AndroidQuicServerTests` (issue #296). This is the big one: bind/accept,
 * stream echo, bidi + uni streams, stream reset, CA-pinned trust, `closeWithError` round-tripping,
 * port sharing with a UDP mux, and rapid bind/connect/close cycles.
 *
 * Apple is a Cloudflare-quiche backend like every other member, so it overrides none of the suite's
 * `open` assertion hooks: `assertResetObservedByPeer` (a peer reset surfaces as
 * [com.ditchoom.buffer.flow.ReadResult.Reset]) and `connectionCloseWithErrorIsObservedByPeer` (quiche
 * sends a real CONNECTION_CLOSE and reads the peer's code back through `quiche_conn_peer_error`) hold
 * here exactly as on JVM/Linux.
 *
 * Cert resolution: [testTlsConfig] is the committed identity (embedded PEM fallback, so it works on a
 * `--standalone` simulator). The two CA-trust tests additionally need the **build-generated**
 * short-lived `localhost` identity, which cannot be embedded — see [AppleTestCerts.requireGenerated].
 * macOS K/N runs from the repo cwd and always has it, so all 16 tests run there; an Apple simulator's
 * `--standalone` cwd never can, so exactly those two skip there and the other 14 still run for real.
 */
class AppleQuicServerTests : QuicServerTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    override fun localhostTlsConfig() =
        QuicTlsConfig(
            certChainPath = AppleTestCerts.requireGenerated("localhost.crt"),
            privKeyPath = AppleTestCerts.requireGenerated("localhost.key"),
        )

    override fun localhostCertPem() = AppleTestCerts.readText(AppleTestCerts.requireGenerated("localhost.crt"))

    override fun unrelatedCaPem() = AppleTestCerts.readText(AppleTestCerts.path("cert.crt"))

    /**
     * Skips only the two tests that actually touch the generated `localhost` identity, and only where it
     * is physically unreachable (see [AppleTestCerts.skippingWhenSimulatorLacksFixtures]). The other 14
     * tests in this suite still run for real on an Apple simulator; on macOS a missing fixture is a hard
     * failure.
     */
    override suspend fun wrapTestBody(block: suspend () -> Unit) = AppleTestCerts.skippingWhenSimulatorLacksFixtures(block)
}
