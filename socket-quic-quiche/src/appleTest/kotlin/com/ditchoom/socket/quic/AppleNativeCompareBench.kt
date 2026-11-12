package com.ditchoom.socket.quic

/** Apple member of [NativeCompareBench]; inert unless NATIVE_BENCH is set. */
class AppleNativeCompareBench : NativeCompareBench() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
