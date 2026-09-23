package com.ditchoom.socket.quic

/**
 * Apple member of [QuicCandidateRaceTestSuite].
 *
 * The lane that matters most for a losing candidate: an Apple client's UDP is an `NWConnection`, and
 * an abandoned attempt that did not cancel it would strand a live NW connection with no error anywhere.
 */
class AppleQuicCandidateRaceTests : QuicCandidateRaceTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
