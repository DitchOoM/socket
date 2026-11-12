package com.ditchoom.socket.quic

/** Apple K/Native member of [LoopConfinementTestSuite]. */
class AppleLoopConfinementTests : LoopConfinementTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
