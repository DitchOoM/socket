package com.ditchoom.socket.quic

/** Apple member of [NativeQuicPerfTrace]; inert unless QUIC_PERF is set. */
class AppleQuicNativePerfTrace : NativeQuicPerfTrace() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
