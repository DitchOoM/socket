@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.quic.toSocketAddress

/**
 * A datagram channel the caller lent a connection or a server, such as the QUIC branch of a demultiplexed port that
 * ICE and STUN share. The core receives from it and sends on it, and nothing more: there is no close here, so the
 * caller's socket outlives every connection on it, and the caller closes it when it is done.
 */
internal class LentChannel(
    private val channel: AddressedDatagramChannel,
) {
    /** The address the caller bound: the connection's own end of every path. */
    val local: SocketAddress get() = channel.localAddress

    suspend fun receive(): DatagramReadResult = channel.receive()

    suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
    ) = channel.send(payload, to)
}

/** Whose datagrams a lent channel's io hands its loop, and where a reply goes. */
internal sealed interface LentPeers {
    /** A client's: its one peer's. A datagram from anywhere else is freed and never reaches quiche. */
    class One(
        val address: SocketAddress,
        val key: PathKey,
    ) : LentPeers

    /** A server's: every peer's. Each reply goes to the address quiche names. */
    data object Every : LentPeers
}

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

/**
 * The peers a lent channel's io has heard from or sent to, each address with its [PathKey], so a datagram's source and
 * a reply's destination are converted once per peer, not per datagram. Holds [size] peers; a new one takes the place of
 * the oldest. The loop thread only.
 */
@LoopOnly
internal class PeerBook(
    first: SocketAddress,
    private val size: Int = PEERS,
) {
    private val firstKey = first.toPathKey()
    private val addresses = Array(size) { first }
    private val keys = Array(size) { firstKey }
    private var held = 0
    private var next = 0

    fun keyOf(address: SocketAddress): PathKey {
        for (i in 0 until held) if (addresses[i] == address) return keys[i]
        val key = address.toPathKey()
        add(address, key)
        return key
    }

    fun addressOf(key: PathKey): SocketAddress {
        for (i in 0 until held) if (keys[i] == key) return addresses[i]
        val address = key.toSocketAddress()
        add(address, key)
        return address
    }

    private fun add(
        address: SocketAddress,
        key: PathKey,
    ) {
        addresses[next] = address
        keys[next] = key
        next = (next + 1) % size
        if (held < size) held++
    }

    private companion object {
        const val PEERS = 64
    }
}
