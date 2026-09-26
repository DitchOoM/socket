package com.ditchoom.socket.quic

/** Apple K/Native member of [PmtuPathEventTestSuite]. */
class ApplePmtuPathEventTests : PmtuPathEventTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
