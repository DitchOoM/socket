package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicConcurrencySoakTestSuite] — the Apple counterpart of
 * [QuicConcurrencySoakTests] (JVM), [LinuxQuicConcurrencySoakTests] and
 * `AndroidQuicConcurrencySoakTests` (issue #296).
 *
 * Many concurrent streams on one connection, many concurrent connections to one endpoint, and a
 * repeated open/close soak — the shape that finds driver-scope leaks and per-peer demux races. Cert
 * resolution is the committed identity via [AppleTestCerts] (runs on the simulator too); K/N compiles
 * quiche via cinterop, so there is no `UnsatisfiedLinkError` skip path.
 */
class AppleQuicConcurrencySoakTests : QuicConcurrencySoakTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
