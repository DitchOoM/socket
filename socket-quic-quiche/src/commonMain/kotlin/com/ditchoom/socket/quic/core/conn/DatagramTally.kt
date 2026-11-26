package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts

/**
 * What a connection's datagram receive read out of quiche's queue, and what the queue dropped. Every datagram quiche
 * was given is read out, still held, or dropped, so of those it received, the ones neither read out nor held were
 * dropped: counted once each, into [counts] and [pressure]. Inside the connection only.
 */
@Confined
internal class DatagramTally(
    private val q: QuicheConnRef,
    private val counts: ConnCounts,
    private val pressure: DatagramPressure,
) {
    /** Datagrams read out of quiche's queue. */
    private var pulled = 0L

    /** Datagrams quiche dropped, counted so far. */
    private var dropped = 0L

    /** A datagram was read out of quiche's queue. */
    fun pulled() {
        pulled++
    }

    /** Counts what quiche dropped while holding [held], from its statistics; a backend that reads none counts none. */
    fun fromQuiche(
        held: Int,
        stood: ReaderStood,
    ): Long =
        when (val stats = q.api.connStats(q.handle)) {
            null -> 0L
            else -> count(stats.dgramRecv, held, stood)
        }

    /** Counts the drops of the [received] datagrams quiche, now holding [held], has not counted yet, the reader where it [stood]. */
    fun count(
        received: Long,
        held: Int,
        stood: ReaderStood,
    ): Long {
        val drops = received - pulled - held - dropped
        if (drops <= 0L) return 0L
        dropped += drops
        counts.datagramsOverflowed(drops)
        pressure.dropped(drops, stood)
        return drops
    }
}
