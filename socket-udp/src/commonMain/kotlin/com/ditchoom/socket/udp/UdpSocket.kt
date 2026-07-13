package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/**
 * The `:socket-udp` entry point — opens real UDP [DatagramChannel]s and resolves addresses.
 *
 * This is the substrate below `Transport` (RFC §5.1): raw UDP is *addressed, unreliable, pre-framed*
 * messages, not a reliable byte stream, so it deliberately does NOT implement the reliable-stream
 * establishment SPI. What it hands back is the buffer-flow datagram trichotomy on real sockets.
 *
 * - [bind] opens an **unconnected** channel (many peers): the SFU/TURN/ICE/DNS/STUN shape, where every
 *   received [com.ditchoom.buffer.flow.Datagram] carries its per-packet source and each send names a
 *   destination.
 * - [connect] opens a **connected** channel (single fixed peer): the QUIC-datagram / `connect()`ed-UDP
 *   shape, where `send(payload, to = null)` targets the fixed peer.
 * - [resolve] is the DNS entry point; it also installs a platform hostname resolver into buffer-flow so
 *   [SocketAddress.resolve] works process-wide (resolved-only model, RFC §10.1).
 *
 * Touching [UdpSocket] at all installs the platform resolver, so [SocketAddress.resolve] is live for
 * downstream code (ICE, quiche) the moment this module is on the classpath and first used.
 */
@ExperimentalDatagramApi
expect object UdpSocket {
    /**
     * Open an **unconnected** UDP channel bound to [localHost]:[localPort]. A `null` [localHost] binds
     * the wildcard address; a [localPort] of `0` picks an ephemeral port. Sends must name a destination.
     */
    suspend fun bind(
        localHost: String? = null,
        localPort: Int = 0,
    ): DatagramChannel

    /**
     * Open a **connected** UDP channel to [remoteHost]:[remotePort] (resolved via [resolve]), bound to
     * [localHost]:[localPort]. `send(payload, to = null)` then targets the fixed peer; only datagrams
     * from that peer are received.
     */
    suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String? = null,
        localPort: Int = 0,
    ): DatagramChannel

    /**
     * Resolve [host]:[port] to a [SocketAddress] that owns its platform representation (zero-alloc reuse
     * as a send target, RFC §4). Numeric literals resolve without a DNS lookup; hostnames perform real
     * DNS off the caller's thread. Delegates to [SocketAddress.resolve] after ensuring the platform
     * resolver is installed.
     */
    suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress
}
