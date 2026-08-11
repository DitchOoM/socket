package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicPassiveMigrationTestSuite] — the Apple counterpart of
 * [QuicPassiveMigrationTests] (JVM), [LinuxQuicPassiveMigrationTests] and
 * `AndroidQuicPassiveMigrationTests` (issue #296).
 *
 * The suite's `supportsPassiveSourceRebind()` hook exists because of the **deleted** Network.framework
 * QUIC backend: its docstring says "the Apple member overrides this to false" since NW's server would
 * not migrate egress to a rebound source (issue #112). There has been no Apple member to do that
 * overriding since #112, and since the June 2026 pivot there is no NW QUIC backend either — Apple's
 * server is the same Cloudflare-quiche server as Linux/JVM, on a dual-stack POSIX UDP socket, doing the
 * same per-source `recv_info` + `sendInfo.to` egress routing. So this member deliberately inherits the
 * default `true` and asserts the real RFC 9000 §9.3 behaviour.
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
