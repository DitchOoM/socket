@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/**
 * Server egress over the shared `:socket-udp` [AddressedDatagramChannel] (Phase 6, adapter-first).
 * One instance per accepted connection wraps the single bound server socket and sends each of that
 * connection's datagrams to the address quiche chose (`sendInfo.to`) from the local address quiche
 * recorded for that path (`sendInfo.from`), both carried as [SendTarget.ServerReply]. Replaces the
 * per-platform server-mode `NioUdpChannel(channel, peerAddr)` egress; the shared socket is owned and
 * closed by the platform server, so [close] is a no-op and [receive] is never called (server packets
 * arrive via the central receive loop).
 *
 * ## Turning a [PathKey] back into an address without reconstruction
 * The driver hands [PathKey]s (opaque, byte-order-unspecified bits — deliberately *not* reversible into
 * an address). Rather than re-derive a sockaddr from one, we resolve it against addresses the receive
 * loop already saw as real [SocketAddress]es:
 * - the connection's original [fixedPeer] (its [fixedPeerKey] computed once at accept) — the common,
 *   non-migrating case, zero map lookup and zero alloc (the owned peer is reused as the send target); and
 * - [peerFor], a lookup into the server's shared PathKey→peer map (populated by the receive loop from
 *   each datagram's `Datagram.peer`) for a *migrated* client whose source address changed (RFC 9000 §9).
 * A lookup miss falls back to [fixedPeer].
 *
 * ## Pinning the reply's source address (#556)
 * [localFor] is the same trick for the *local* side: a lookup into the server's PathKey→local map,
 * populated by the receive loop from each datagram's `Datagram.localAddress`. Its result becomes
 * [DatagramSendOptions.fromLocal], so a wildcard-bound server answers from the address the client
 * actually addressed instead of whichever one the kernel prefers — the reply a `connect()`ed client on
 * another local address of the same host would otherwise drop as off-path.
 *
 * A miss, or a `from` whose family is 0 (a backend that decodes no egress address), leaves the source
 * unnamed and lets the platform choose. That is the pre-#556 behaviour, kept deliberately as the
 * fallback: it is what a channel without [com.ditchoom.buffer.flow.DatagramCapabilities.sourceAddressSelect]
 * can do, and naming a source such a channel would silently ignore is a worse lie than not naming one.
 */
internal class ServerConnectionUdpChannel(
    private val channel: AddressedDatagramChannel,
    private val fixedPeer: SocketAddress,
    private val fixedPeerKey: PathKey,
    private val peerFor: (PathKey) -> SocketAddress?,
    private val localFor: (PathKey) -> SocketAddress?,
) : UdpChannel {
    override suspend fun receive(buffer: PlatformBuffer): Int =
        throw UnsupportedOperationException("server egress channel does not receive")

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        target: SendTarget,
    ): SendOutcome {
        val peer: SocketAddress
        val options: DatagramSendOptions
        when (target) {
            SendTarget.ConnectedPeer -> {
                peer = fixedPeer
                options = DatagramSendOptions.Default
            }

            is SendTarget.ServerReply -> {
                peer = if (target.to == fixedPeerKey) fixedPeer else peerFor(target.to) ?: fixedPeer
                options = optionsFor(target.from)
            }
        }
        buffer.position(0)
        buffer.setLimit(len)
        return sendOutcomeOf { channel.send(buffer, to = peer, options = options) }
    }

    /**
     * The send options that pin this reply's source — or, in both of the cases that cannot, the ones
     * that leave the choice to the platform.
     *
     * The two "cannot" branches stay separate rather than collapsing into one absent-source case: they
     * end in the same options but mean different things, and only one of them is a defect (see
     * [ReplySource]).
     */
    private fun optionsFor(source: ReplySource): DatagramSendOptions =
        when (source) {
            // Nothing to pin, and inventing an address would be worse than the platform's own choice.
            // Permanent and expected for this backend, so it is not worth reporting.
            ReplySource.Undecodable -> DatagramSendOptions.Default
            is ReplySource.Recorded ->
                when (val local = localFor(source.key)) {
                    // quiche named a path whose local address the receive loop never recorded. Every
                    // address quiche can name here was given to it by that loop, so this is this
                    // server's own bookkeeping having lost one — not a state the network can cause.
                    // Send anyway (dropping the datagram would be a worse answer than an unpinned one)
                    // with the source unnamed, exactly as a channel that cannot select one would.
                    null -> DatagramSendOptions.Default
                    else -> DatagramSendOptions(fromLocal = local)
                }
        }

    /** The shared server socket is owned and closed by the platform server, never per-connection. */
    override fun close() = Unit
}
