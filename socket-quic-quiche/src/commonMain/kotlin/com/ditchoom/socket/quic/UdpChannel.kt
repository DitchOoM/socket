package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer

/**
 * Platform-specific UDP datagram channel.
 *
 * Abstracts the platform I/O mechanism (NIO Selector on JVM, io_uring on Linux)
 * so the [QuicheDriver] event loop is platform-neutral.
 */
interface UdpChannel {
    /**
     * Whether this channel allocates its own receive buffer and hands it out via [receiveOwned] rather
     * than filling a caller-provided one in [receive]. When true, the [QuicheDriver] reader loop consumes
     * [receiveOwned] and never calls [receive] — the datagram lands straight in the channel-allocated
     * (pooled) buffer, so there is no receive copy. Default false: the driver pre-allocates from its pool
     * and calls [receive] (the classic push seam — every test double and the legacy io_uring/NIO proxy
     * channels use it).
     */
    val ownsReceiveBuffer: Boolean get() = false

    /** Receive one UDP datagram into [buffer]. Suspends until data arrives. Returns bytes received. */
    suspend fun receive(buffer: PlatformBuffer): Int

    /**
     * Zero-copy receive for an [ownsReceiveBuffer] channel: suspend until one whole datagram arrives and
     * return it in a channel-allocated buffer whose ownership transfers to the caller (the driver frees
     * it after `quiche_conn_recv`; a pooled factory's `freeNativeMemory()` returns it to the pool).
     * Implementations absorb transient re-arm/timeout retries internally and suspend indefinitely once the
     * socket is permanently closed — the driver cancels this reader during teardown — so this never
     * signals a non-terminal "no data yet". Only invoked when [ownsReceiveBuffer] is true; the default
     * throws.
     */
    suspend fun receiveOwned(): OwnedDatagram = throw UnsupportedOperationException("channel does not own its receive buffer")

    /**
     * Send [len] bytes from [buffer]. [SendTarget.ConnectedPeer] sends to the channel's own
     * connected/fixed peer (the common case). [SendTarget.ServerReply] — set by the server egress path from quiche's
     * `sendInfo.to`/`sendInfo.from` — names both the destination, so replies follow a migrated peer to
     * its new source address, and the local address to leave from, so a wildcard-bound server does not
     * let the kernel pick a source the client will drop (#556). Channels bound to exactly one 4-tuple
     * ignore [target] entirely; a channel that can target a destination but cannot select a source
     * honours the destination and ignores the source.
     *
     * **Reports failure, never throws it.** Returns [SendOutcome.Failed] with a typed
     * [com.ditchoom.socket.udp.DatagramSendError] rather than raising, so the caller is made to
     * decide per cause instead of inheriting one blanket policy from a `catch` — see [SendOutcome]
     * for why that distinction is load-bearing. Cancellation is the sole exception and still
     * propagates: it is lifecycle, not a network event.
     */
    suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        target: SendTarget = SendTarget.ConnectedPeer,
    ): SendOutcome

    /** Close the underlying socket. */
    fun close()
}

/**
 * One datagram received into a channel-owned buffer, returned by [UdpChannel.receiveOwned]. Ownership of
 * [buffer] transfers to the caller; its readable window is `[0, length)` and [buffer]'s native address is
 * the datagram's start.
 */
class OwnedDatagram(
    val buffer: PlatformBuffer,
    val length: Int,
)
