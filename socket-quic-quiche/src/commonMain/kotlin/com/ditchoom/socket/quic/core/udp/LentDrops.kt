package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.observe.Counter

/** Why a lent channel's io dropped a datagram, each counted where the drop happened. */
internal enum class LentDrop {
    /** Received from a source other than the client's one peer. */
    Foreign,

    /** Received while every arrival slot held a datagram the loop had not taken. */
    ArrivalsFull,

    /** Received larger than a slot holds. */
    TooLarge,

    /**
     * A server's datagram that named no peer. Server connections address every datagram; the send batch's target type
     * is shared with client sockets, so this arm stays, counted.
     */
    Unaddressed,

    /** The channel, still open, refused the send as larger than its path carries. */
    SendTooLarge,

    /** The channel, still open, refused the send as unreachable: no route, or the peer's port is closed (ICMP). */
    SendUnreachable,

    /** The channel, still open, refused the send as one it could not take now. */
    SendWouldBlock,

    /** The channel, still open, refused the send for another reason it named. */
    SendRefused,
}

/** The datagrams one lent channel's io dropped, by [LentDrop]: any thread adds, any thread reads. */
internal class LentDrops {
    private val counts = Array(LentDrop.entries.size) { Counter() }

    fun dropped(why: LentDrop) = counts[why.ordinal].increment()

    fun count(why: LentDrop): Long = counts[why.ordinal].value()
}

/** What a socket's io drops itself, beside what the kernel drops ([SocketReport.drops]). */
internal sealed interface IoDrops {
    /** A socket the loop owns: the kernel is the only one that drops. */
    data object Kernel : IoDrops

    /** A lent channel: its io counts its own. */
    class Lent(
        val drops: LentDrops,
    ) : IoDrops
}
