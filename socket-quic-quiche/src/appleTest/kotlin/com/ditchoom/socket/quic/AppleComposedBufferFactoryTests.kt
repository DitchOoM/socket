package com.ditchoom.socket.quic

/** Apple member of [ComposedBufferFactoryTestSuite]. */
class AppleComposedBufferFactoryTests : ComposedBufferFactoryTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
