package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicLargePayloadTestSuite] — the Apple counterpart of
 * [QuicLargePayloadTests] (JVM), [LinuxQuicLargePayloadTests] and `AndroidQuicLargePayloadTests`
 * (issue #296).
 *
 * Pushes multi-megabyte payloads through one stream and through several concurrent streams, so the
 * Apple datapath is exercised well past a single MTU: flow-control window updates, the send/recv
 * segmentation in `DriverStreamAdapter`, and the `NWConnection` UDP egress under sustained load.
 * Cert resolution is the committed identity via [AppleTestCerts] (runs on the simulator too); K/N
 * compiles quiche via cinterop, so there is no `UnsatisfiedLinkError` skip path.
 */
class AppleQuicLargePayloadTests : QuicLargePayloadTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
