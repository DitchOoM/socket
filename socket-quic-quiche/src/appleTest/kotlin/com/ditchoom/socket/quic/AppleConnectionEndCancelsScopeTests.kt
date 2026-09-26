package com.ditchoom.socket.quic

/** Apple member of [ConnectionEndCancelsScopeTestSuite]: an `NWConnection` client against the POSIX UDP server. */
class AppleConnectionEndCancelsScopeTests : ConnectionEndCancelsScopeTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
