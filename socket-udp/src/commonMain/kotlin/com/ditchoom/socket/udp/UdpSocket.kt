package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/** The classic UDP payload ceiling (65535 − 8 UDP − 20 IPv4). The default per-datagram staging size. */
const val MAX_UDP_DATAGRAM_SIZE: Int = 65507

/**
 * The default [BufferFactory] each channel allocates its per-datagram receive payload from when a
 * caller does not inject its own. This is the platform's proven native-capable factory — [BufferFactory.Default]
 * on the JVM/Android (a NIO-writable direct buffer), a native deterministic factory on Linux/Apple (io_uring
 * `recvmsg` / `recvfrom` and `NWConnection` all require raw native memory, which `BufferFactory.Default` is
 * *not* on Linux). Kept per-platform via `expect`/`actual` so the default never changes a platform's existing,
 * validated allocation strategy.
 */
internal expect val defaultDatagramBufferFactory: BufferFactory

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
     *
     * [receiveBufferSize] bounds the per-datagram staging buffer each `receive()` allocates (the largest
     * datagram delivered without truncation). It defaults to the UDP payload ceiling; a caller that knows
     * its datagrams are small — e.g. the QUIC datapath, whose `max_recv_udp_payload_size` is ~1350 — passes
     * a smaller value to avoid a 64 KB allocation per received packet.
     *
     * [bufferFactory] is where each received [com.ditchoom.buffer.flow.Datagram]'s payload is allocated from
     * — the allocate-and-transfer hook (RFC §4, [feedback_zero_copy_buffers]). It defaults to the platform's
     * native-capable factory; a caller with its own pool injects it here (e.g. quiche passes its `recvBufPool`,
     * a `BufferPool` — which *is* a `BufferFactory`) so the kernel lands each datagram straight in a pooled
     * buffer with no downstream copy. Ownership of the allocated payload transfers out on `receive()`; the
     * consumer frees it (a pooled factory's `freeNativeMemory()` returns it to the pool).
     */
    suspend fun bind(
        localHost: String? = null,
        localPort: Int = 0,
        receiveBufferSize: Int = MAX_UDP_DATAGRAM_SIZE,
        bufferFactory: BufferFactory = defaultDatagramBufferFactory,
    ): DatagramChannel

    /**
     * Open a **connected** UDP channel to [remoteHost]:[remotePort] (resolved via [resolve]), bound to
     * [localHost]:[localPort]. `send(payload, to = null)` then targets the fixed peer; only datagrams
     * from that peer are received. [receiveBufferSize] and [bufferFactory] behave as in [bind].
     */
    suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String? = null,
        localPort: Int = 0,
        receiveBufferSize: Int = MAX_UDP_DATAGRAM_SIZE,
        bufferFactory: BufferFactory = defaultDatagramBufferFactory,
    ): DatagramChannel

    /**
     * Open a **multicast-capable** UDP channel bound to the wildcard address of [family] on [port] (`0`
     * picks an ephemeral port, but a multicast receiver almost always binds the group's well-known port).
     * The returned [MulticastDatagramChannel] is bound and configured but **not yet joined to any group** —
     * call [MulticastDatagramChannel.joinGroup] to start receiving one. Sending to a group is an ordinary
     * `send(payload, to = groupAddress)`.
     *
     * `SO_REUSEADDR` (and `SO_REUSEPORT` where the platform has it) is set so several receivers on the same
     * host can bind the same multicast port — the normal multicast-listener arrangement. [family] selects
     * IPv4 (`AF_INET`, wildcard `0.0.0.0`) or IPv6 (`AF_INET6`, wildcard `::`); it must match the family of
     * every group later joined on this channel.
     *
     * [receiveBufferSize] and [bufferFactory] behave exactly as in [bind].
     */
    suspend fun bindMulticast(
        port: Int,
        family: AddressFamily = AddressFamily.IPv4,
        receiveBufferSize: Int = MAX_UDP_DATAGRAM_SIZE,
        bufferFactory: BufferFactory = defaultDatagramBufferFactory,
    ): MulticastDatagramChannel

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
