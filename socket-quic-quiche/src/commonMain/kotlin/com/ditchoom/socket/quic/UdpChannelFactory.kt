package com.ditchoom.socket.quic

/**
 * Opens additional client UDP sockets for connection migration (slice 3).
 *
 * The originating [withQuicConnection] knows the peer; a factory captures it so
 * the [QuicheDriver] — which is platform-neutral — can open a second path socket
 * (bound to a different local address, connected to the same peer) without
 * knowing how sockets are created on each platform.
 *
 * Only the client migrates, so only client connection setups construct one;
 * server-accepted drivers and the no-migration platforms (Apple, JS) leave it
 * null and migration is unsupported there.
 */
interface UdpChannelFactory {
    /**
     * Open a UDP socket bound to [localHost]:[localPort] (null host = default
     * interface, 0 port = ephemeral), connected to the same peer as the
     * originating connection.
     *
     * The returned [NewPath.localSockAddrAddress]/[NewPath.localSockAddrLength]
     * point at pinned native memory holding the socket's resolved local sockaddr
     * (for quiche's probe/migrate calls and recv_info). The driver owns the
     * returned channel and must [UdpChannel.close] it and call [NewPath.release]
     * when the path is torn down.
     */
    suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath
}

/**
 * A freshly-opened migration path: the channel plus its pinned local sockaddr and
 * a hook to free that sockaddr's backing memory. The driver decodes
 * [localSockAddrAddress] into a [PathKey] to route datagrams to this socket.
 */
class NewPath(
    val channel: UdpChannel,
    val localSockAddrAddress: Long,
    val localSockAddrLength: Int,
    val release: () -> Unit,
)
