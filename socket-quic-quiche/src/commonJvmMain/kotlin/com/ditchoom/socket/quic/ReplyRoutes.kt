package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.SocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * peer -> the socket its last datagram arrived on, for [PerLocalAddressServerChannel].
 *
 * Keyed by the peer address itself, as the datagram that carried it compares: every address a reply
 * is sent to came from a received datagram, so the two are the same implementation and equal when
 * they name the same endpoint. Nothing is derived from the address per datagram.
 *
 * **Bounded, least recently seen first.** The key is whatever source a datagram *claims*, and UDP
 * lets a peer claim anything: unbounded, a spray of spoofed sources would grow this without limit.
 * Evicting a live peer costs at most one reply routed by the fallback, because its next datagram
 * puts it back.
 *
 * **One writer, any number of readers, no lock.** [arrived] is called by the channel's one receiver
 * and keeps the recency order in a map only it touches; what it learns is published to a concurrent
 * map that every connection's sends read with [routeTo].
 */
internal class ReplyRoutes(
    private val bound: Int,
) {
    private val published = ConcurrentHashMap<SocketAddress, AddressedDatagramChannel>()

    // Receiver only. Access-ordered, so the eldest entry is the peer heard from longest ago.
    private val recency =
        object : LinkedHashMap<SocketAddress, AddressedDatagramChannel>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SocketAddress, AddressedDatagramChannel>): Boolean {
                if (size <= bound) return false
                published.remove(eldest.key)
                return true
            }
        }

    /** How many peers hold a route. */
    val size: Int get() = published.size

    /** A datagram from [peer] arrived on [socket]. Receiver only. */
    fun arrived(
        peer: SocketAddress,
        socket: AddressedDatagramChannel,
    ) {
        if (recency.put(peer, socket) !== socket) published[peer] = socket
    }

    /** The socket [peer]'s last datagram arrived on, or [otherwise] for a peer that holds no route. */
    fun routeTo(
        peer: SocketAddress,
        otherwise: AddressedDatagramChannel,
    ): AddressedDatagramChannel = published[peer] ?: otherwise
}
