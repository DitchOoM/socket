@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.path.FeedWake
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.transport.NetworkId
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The socket that carries a client's connection: its [ticket], the address it is bound to ([local]), and its [network]. */
internal class Carried(
    val ticket: SocketTicket,
    val local: PathKey,
    val network: NetworkId,
)

/**
 * Which socket carries a client's connection, as its loop publishes it: written on the loop each time a socket starts
 * carrying (the connect, a promotion, a reopen on another network), a reading replaced whole, and read by the network
 * feed, which each write wakes. The feed holds no copy of where the socket is.
 */
@Published
internal class CarriedCell(
    private val wake: FeedWake,
    first: Carried,
) {
    private val now = AtomicReference(first)

    fun read(): Carried = now.load()

    /** Socket [ticket], bound to [local] and on [network], carries from now on. */
    fun carries(
        ticket: SocketTicket,
        local: PathKey,
        network: NetworkId,
    ) {
        now.store(Carried(ticket, local, network))
        wake.poke()
    }
}
