@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.toPathKey

/**
 * A datagram channel the caller lent a connection or a server, such as the QUIC branch of a demultiplexed port that
 * ICE and STUN share, with the claim that makes this connect or serve its one QUIC user ([LentChannels], by the channel
 * object's identity: a wrapper and the channel it wraps are two channels to it). The core
 * receives from it and sends on it, and nothing more: there is no close here, so the caller's socket outlives every
 * connection on it, and the caller closes it when it is done. Direct peer-to-peer QUIC runs one QUIC role, a client or
 * a server, per channel.
 */
internal class LentChannel(
    private val channel: AddressedDatagramChannel,
    val claim: LentClaim,
) {
    /** The address the caller bound: the connection's own end of every path. */
    val local: SocketAddress get() = channel.localAddress

    /** Whether the caller's channel is still open: only its close ends a lent io. */
    fun isOpen(): Boolean = channel.isOpen

    suspend fun receive(): DatagramReadResult = channel.receive()

    suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
    ) = channel.send(payload, to)
}

/** Whose datagrams a lent channel's io hands its loop, and where a reply goes. */
internal sealed interface LentPeers {
    /**
     * A client's: its one peer's, in the channel's own family ([sendTo]) and as dialled ([dialled]); a datagram from
     * anywhere else is dropped and never reaches quiche. [key] is the peer as quiche knows it.
     */
    class One(
        val sendTo: SocketAddress,
        val dialled: SocketAddress,
        val key: PathKey,
    ) : LentPeers

    /** A server's: every peer's. Each reply goes to the address quiche names. */
    data object Every : LentPeers
}

/**
 * The one peer of a client on [channel], dialled as [peer] ([key] as quiche knows it). On a dual-stack channel an IPv4
 * peer arrives and is sent to as its IPv4-mapped IPv6 address, which is the same peer: the mapped form is made once,
 * here.
 */
internal fun onePeerOn(
    channel: LentChannel,
    peer: SocketAddress,
    key: PathKey,
): LentPeers.One {
    val mapped =
        if (channel.local.family == AddressFamily.IPv6 && peer.family == AddressFamily.IPv4) {
            SocketAddress.ofLiteral(IPV4_MAPPED + peer.host, peer.port)
        } else {
            peer
        }
    return LentPeers.One(mapped, peer, key)
}

/** The socket a client on this ride's channel talks to [peer] on ([key] as quiche knows it), its slots from [factory]. */
internal fun ClientRide.Lent.socketTo(
    peer: SocketAddress,
    key: PathKey,
    factory: BufferFactory,
): StackSocket.Lent = StackSocket.Lent(channel, onePeerOn(channel, peer, key), channel.local.toPathKey(), factory)

/** Whether [source] is the client's one peer in either form it was made in at setup. */
internal fun LentPeers.One.isFrom(source: SocketAddress): Boolean = source == sendTo || source == dialled

/**
 * Whether [source], which a platform reports in a form of its own, is the peer [key]: the same port and address, an
 * IPv4 address and its IPv4-mapped IPv6 form being one.
 */
internal fun isSamePeer(
    source: PathKey,
    key: PathKey,
): Boolean = source.port == key.port && v4Bits(source) == v4Bits(key) && v4Bits(key) != NOT_V4 || source == key

/** The low word of an IPv4 address in either form, or [NOT_V4]. */
private fun v4Bits(key: PathKey): Long =
    when {
        key.family == V4 -> key.lo
        key.family == V6 && key.hi == 0L && key.lo ushr V4_SHIFT == MAPPED_MARK -> key.lo and V4_MASK
        else -> NOT_V4
    }

private const val IPV4_MAPPED = "::ffff:"
private const val V4 = 4
private const val V6 = 6
private const val V4_SHIFT = 32
private const val MAPPED_MARK = 0xFFFFL
private const val V4_MASK = 0xFFFFFFFFL
private const val NOT_V4 = 0x1_0000_0000L

/**
 * The transport options a connection or a server runs with on a lent channel: GREASE of the fixed bit is off, since a
 * peer allowed to grease it sends short-header packets whose first byte lands in the DTLS and STUN ranges, which no
 * demultiplexer can classify (RFC 9443 section 3).
 */
internal fun QuicOptions.onLentChannel(): QuicOptions = if (enableGrease) copy(enableGrease = false) else this

/** The options a connection on [this] ride runs with: on a lent channel, as [onLentChannel] says. */
internal fun ClientRide.optionsFor(options: QuicOptions): QuicOptions =
    when (this) {
        is ClientRide.OwnSocket, is ClientRide.NetworkFramework -> options
        is ClientRide.Lent -> options.onLentChannel()
    }

/** Whether a connection on [this] ride judges its path's silence: never on a lent channel, which no move can leave. */
internal fun ClientRide.silenceOn(
    policy: MigrationPolicy,
    monitor: NetworkMonitor,
): Silence =
    when (this) {
        is ClientRide.OwnSocket, is ClientRide.NetworkFramework -> silenceOf(policy, monitor)
        is ClientRide.Lent -> Silence.Off
    }

/** Runs [follow], which feeds the connection the network's changes, unless the ride is lent: its owner follows it. */
internal inline fun ClientRide.followsNetwork(follow: () -> Unit) =
    when (this) {
        is ClientRide.OwnSocket, is ClientRide.NetworkFramework -> follow()
        is ClientRide.Lent -> Unit
    }
