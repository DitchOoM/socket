package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicPassiveMigrationTestSuite] — the Apple counterpart of
 * [QuicPassiveMigrationTests] (JVM), [LinuxQuicPassiveMigrationTests] and
 * `AndroidQuicPassiveMigrationTests` (issue #296).
 *
 * The suite used to offer a `supportsPassiveSourceRebind()` hook whose docstring said "the Apple member
 * overrides this to false", because the **deleted** Network.framework QUIC backend's server would not
 * migrate egress to a rebound source (issue #112). No Apple member ever did that overriding, and since
 * the June 2026 pivot there is no NW QUIC backend either — Apple's server is the same Cloudflare-quiche
 * server as Linux/JVM, on a dual-stack POSIX UDP socket, doing the same per-source `recv_info` +
 * `sendInfo.to` egress routing. The hook is gone; this member asserts the real RFC 9000 §9.3 behaviour
 * with no way to opt out of it.
 *
 * The rebinding proxy is the Darwin twin of the Linux io_uring one — see [ApplePosixUdpForwarder].
 */
class AppleQuicPassiveMigrationTests : QuicPassiveMigrationTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    override fun createRebindingProxy(serverPort: Int): RebindingProxy = PosixRebindingProxy(serverPort)

    /** Straight pass-through in both directions; [rebind] just swaps the upstream source port. */
    private class PosixRebindingProxy(
        serverPort: Int,
    ) : ApplePosixUdpForwarder(serverPort),
        RebindingProxy {
        init {
            start()
        }

        override val proxyPort: Int get() = boundPort

        override suspend fun onClientToServer(datagram: ByteArray) = forwardUpstream(datagram)

        override suspend fun onServerToClient(datagram: ByteArray) = forwardToClient(datagram)

        override fun rebind() = swapUpstream()

        override suspend fun close() = shutdown()
    }
}
