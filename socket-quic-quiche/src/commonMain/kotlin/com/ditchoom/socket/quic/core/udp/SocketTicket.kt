package com.ditchoom.socket.quic.core.udp

import kotlin.jvm.JvmInline

/**
 * Which of a client's sockets a datagram leaves from or arrived on, and what a message about that socket names. The
 * socket a connection opens on holds [FIRST]; each socket its loop opens beside the carrying one holds the next. A
 * ticket names one socket for good: once the loop closed or promoted that socket, a message naming another state of it
 * finds nothing to act on and is a no-op, and a datagram tagged with a ticket no socket holds goes nowhere.
 */
@JvmInline
internal value class SocketTicket(
    val value: Int,
) {
    fun next(): SocketTicket = SocketTicket(value + 1)

    companion object {
        val FIRST = SocketTicket(0)
    }
}
