package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicIdleTimeoutTestSuite] — the Apple counterpart of [QuicIdleTimeoutTests]
 * (JVM), [LinuxQuicIdleTimeoutTests] and `AndroidQuicIdleTimeoutTests` (issue #296).
 *
 * Drives QUIC's idle timeout and the keepalive PING that must hold a connection open past it, on the
 * Apple quiche client (UDP over `NWConnection`) against the dual-stack POSIX server. Cert resolution is
 * the committed identity via [AppleTestCerts], so it runs on the `--standalone` simulator too; K/N
 * compiles quiche via cinterop, so there is no `UnsatisfiedLinkError` skip path.
 */
class AppleQuicIdleTimeoutTests : QuicIdleTimeoutTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
