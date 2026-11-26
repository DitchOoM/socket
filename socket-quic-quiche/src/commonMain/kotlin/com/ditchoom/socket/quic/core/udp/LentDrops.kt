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

    /** Refused by the channel's send: unreachable, too large, would block; the channel stayed open. */
    SendRefused,

    /**
     * A server's datagram that named no peer. Server connections address every datagram ([SendTarget.ServerReply]);
     * the send batch's target type is shared with client sockets, so this arm stays, counted.
     */
    Unaddressed,
}

/** The datagrams one lent channel's io dropped, by [LentDrop]: any thread adds, any thread reads. */
internal class LentDrops {
    private val foreign = Counter()
    private val arrivalsFull = Counter()
    private val tooLarge = Counter()
    private val sendRefused = Counter()
    private val unaddressed = Counter()

    fun dropped(why: LentDrop) = counter(why).increment()

    fun count(why: LentDrop): Long = counter(why).value()

    private fun counter(why: LentDrop): Counter =
        when (why) {
            LentDrop.Foreign -> foreign
            LentDrop.ArrivalsFull -> arrivalsFull
            LentDrop.TooLarge -> tooLarge
            LentDrop.SendRefused -> sendRefused
            LentDrop.Unaddressed -> unaddressed
        }
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
