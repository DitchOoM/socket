@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/**
 * Server egress over the shared unconnected `:socket-udp` [DatagramChannel] (Phase 6, adapter-first).
 * One instance per accepted connection wraps the single bound server socket and sends each of that
 * connection's datagrams to the address quiche chose (`sendInfo.to`, decoded to a [PathKey] by the
 * driver). Replaces the per-platform server-mode `NioUdpChannel(channel, peerAddr)` /
 * `ServerConnectionUdpChannel` egress; the shared socket is owned and closed by the platform server, so
 * [close] is a no-op and [receive] is never called (server packets arrive via the central receive loop).
 *
 * ## Turning a [PathKey] back into a send target without reconstruction
 * The driver hands a [PathKey] (opaque, byte-order-unspecified bits — deliberately *not* reversible into
 * an address). Rather than re-derive a sockaddr from it, we resolve it against addresses the receive loop
 * already saw as real [SocketAddress]es:
 * - the connection's original [fixedPeer] (its [fixedPeerKey] computed once at accept) — the common,
 *   non-migrating case, zero map lookup and zero alloc (the owned peer is reused as the send target); and
 * - [peerFor], a lookup into the server's shared PathKey→peer map (populated by the receive loop from
 *   each datagram's `Datagram.peer`) for a *migrated* client whose source address changed (RFC 9000 §9).
 * A `null` `dest`, or a lookup miss, falls back to [fixedPeer].
 */
internal class ServerConnectionUdpChannel(
    private val channel: DatagramChannel,
    private val fixedPeer: SocketAddress,
    private val fixedPeerKey: PathKey,
    private val peerFor: (PathKey) -> SocketAddress?,
) : UdpChannel {
    override suspend fun receive(buffer: PlatformBuffer): Int =
        throw UnsupportedOperationException("server egress channel does not receive")

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        val target =
            when {
                dest == null || dest == fixedPeerKey -> fixedPeer
                else -> peerFor(dest) ?: fixedPeer
            }
        buffer.position(0)
        buffer.setLimit(len)
        channel.send(buffer, to = target)
    }

    /** The shared server socket is owned and closed by the platform server, never per-connection. */
    override fun close() = Unit
}
