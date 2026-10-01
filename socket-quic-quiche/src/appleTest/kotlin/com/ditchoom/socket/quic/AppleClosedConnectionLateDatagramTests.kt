package com.ditchoom.socket.quic

/** Apple member of [ClosedConnectionLateDatagramTestSuite]: the POSIX UDP server under Kotlin/Native. */
class AppleClosedConnectionLateDatagramTests : ClosedConnectionLateDatagramTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
