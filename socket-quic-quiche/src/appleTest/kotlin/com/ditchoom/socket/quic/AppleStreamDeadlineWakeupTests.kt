package com.ditchoom.socket.quic

/** Apple member of [NativeStreamDeadlineWakeupTests]. */
class AppleStreamDeadlineWakeupTests : NativeStreamDeadlineWakeupTests() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
