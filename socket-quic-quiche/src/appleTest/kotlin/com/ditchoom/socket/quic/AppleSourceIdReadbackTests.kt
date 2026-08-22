package com.ditchoom.socket.quic

/** Apple K/Native member of [SourceIdReadbackTestSuite] — covers the Apple cinterop binding. */
class AppleSourceIdReadbackTests : SourceIdReadbackTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
