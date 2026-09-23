package com.ditchoom.socket.quic

/** Apple K/Native member of [SessionResumptionTestSuite]. */
class AppleSessionResumptionTests : SessionResumptionTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
