@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.awaitCancellation

/**
 * Client egress and ingress over a UDP port this connection does **not** own —
 * [QuicClientBinding.Shared], the client half of RFC 9443 port sharing. One instance per connection
 * wraps the demultiplexed QUIC branch and sends every datagram to the peer resolved at connect time.
 *
 * The counterpart to [DatagramChannelUdpChannel], which adapts a *connected* channel. A shared branch
 * is an [AddressedDatagramChannel] instead — unconnected, one socket serving several protocols — so
 * the peer travels with the adapter rather than with the socket.
 *
 * ## [close] does not close the socket
 * The port belongs to whoever bound it (an ICE agent, a demultiplexer). Closing this connection must
 * leave the port serving DTLS, STUN and everything else on it, so this releases nothing — exactly as
 * [ServerConnectionUdpChannel] does on the server side.
 *
 * ## Buffer ownership
 * [ownsReceiveBuffer] is true and the driver consumes [receiveOwned], but the pooled buffer each
 * datagram arrives in came from the **owner's** buffer factory, not this connection's `recvBufPool`.
 * That is why a port meant to carry QUIC should be bound with the pool the QUIC stack will use: the
 * driver frees each buffer back to whichever pool allocated it, so the two are consistent either way,
 * but only a shared pool keeps the receive path allocation-free.
 *
 * ## ⚠️ Why a datagram from another peer is dropped rather than fed to quiche
 * A client driver runs [DatagramIngress.DriverReaderLoop], and such a reader has exactly one
 * `recv_info` — fixed at construction, naming this connection's peer. Feeding it a datagram that came
 * from somewhere else would tell quiche that packet arrived on this path from this peer, which is
 * false. quiche would almost certainly discard it anyway (the DCID will not match), but "almost
 * certainly discarded" is not a routing rule. So the peer is checked here, where the real source is
 * still known, and a foreign datagram is freed back to its pool instead of being misattributed.
 *
 * A well-formed deployment produces none: the demultiplexer's contract is to deliver only
 * QUIC-classified datagrams, and a port carrying a second QUIC endpoint needs connection-ID routing,
 * which is the server's job and not something a single client connection can do.
 */
internal class ClientSharedPortUdpChannel(
    private val channel: AddressedDatagramChannel,
    private val peer: SocketAddress,
) : UdpChannel {
    override val ownsReceiveBuffer: Boolean = true

    override suspend fun receiveOwned(): OwnedDatagram {
        while (true) {
            when (val result = channel.receive()) {
                is DatagramReadResult.Received -> {
                    val payload = result.datagram.payload
                    if (result.datagram.peer == peer) {
                        return OwnedDatagram(payload, payload.remaining())
                    }
                    // Not ours. Free it back to the owner's pool and keep reading, rather than
                    // handing quiche a packet under this connection's fixed recv_info.
                    payload.freeNativeMemory()
                }

                is DatagramReadResult.Closed ->
                    // The owner closed the port. Park until the driver cancels this reader during
                    // teardown, so the loop never busy-spins on a terminal state.
                    awaitCancellation()
            }
        }
    }

    override suspend fun receive(buffer: PlatformBuffer): Int {
        while (true) {
            when (val result = channel.receive()) {
                is DatagramReadResult.Received -> {
                    val payload = result.datagram.payload
                    val ours = result.datagram.peer == peer
                    val length = payload.remaining()
                    if (ours) {
                        buffer.position(0)
                        buffer.write(payload)
                    }
                    payload.freeNativeMemory()
                    if (ours) return length
                }

                is DatagramReadResult.Closed -> awaitCancellation()
            }
        }
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        target: SendTarget,
    ): SendOutcome {
        // A client names neither destination nor source: [target] is always ConnectedPeer here, and
        // the source is the shared socket's, which this connection does not get to choose.
        buffer.position(0)
        buffer.setLimit(len)
        return sendOutcomeOf { channel.send(buffer, peer) }
    }

    /** The owner's socket outlives this connection. See the class KDoc. */
    override fun close() = Unit
}
