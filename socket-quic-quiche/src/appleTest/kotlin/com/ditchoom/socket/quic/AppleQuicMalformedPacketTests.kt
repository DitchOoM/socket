package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicMalformedPacketTestSuite] — the Apple counterpart of
 * [QuicMalformedPacketTests] (JVM), [LinuxQuicMalformedPacketTests] and
 * `AndroidQuicMalformedPacketTests` (issue #296).
 *
 * Sprays garbage/truncated/oversized datagrams straight at the server's UDP port and asserts the
 * server neither crashes nor stops serving real clients. On Apple the server is the dual-stack POSIX
 * UDP socket, so this is the first coverage of its `recvfrom` accept loop against input quiche rejects.
 * The raw injector is a throwaway BSD socket ([ApplePosixUdp.sendToLoopback]) — the Darwin counterpart
 * of the Linux member's `sendto`.
 *
 * Cert resolution is the committed identity via [AppleTestCerts] (runs on the simulator too); K/N
 * compiles quiche via cinterop, so there is no `UnsatisfiedLinkError` skip path.
 */
class AppleQuicMalformedPacketTests : QuicMalformedPacketTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    override suspend fun sendRawDatagram(
        port: Int,
        bytes: ByteArray,
    ) = ApplePosixUdp.sendToLoopback(port, bytes)
}
