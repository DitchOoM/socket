package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** The most datagrams one batch receive is lent at once, and so the most datagram slots a connection holds. */
internal const val LEND_WINDOW = 256

/** Memory a datagram is read into and lent from. */
@Confined
private class Slot(
    val bytes: NativeBytes,
)

/** Where the batch reader stands: away with what it was last lent, if anything; parked for datagrams; or told they arrived and not yet back. */
private sealed interface BatchReader {
    data object Away : BatchReader

    class Parked(
        val waiter: BatchWaiter,
    ) : BatchReader

    data object Told : BatchReader
}

/**
 * A connection's received RFC 9221 datagrams, inside it only. They stay in quiche's receive queue, [bound] long, until a
 * receive takes them: quiche drops the oldest for the newest when it is full, and the box counts each drop as
 * received but neither read out nor held. A receive is answered from quiche straight into its own memory, else parks
 * until a datagram arrives. A batch receive is lent at most [LEND_WINDOW] datagrams, read into slots the box keeps and
 * read in place, and gives them back as the next batch is lent. One past [incoming] is dropped, counted, when it would
 * be delivered.
 */
@Confined
internal class DatagramBox(
    private val q: QuicheConnRef,
    private val bound: Int,
    incoming: AgeBound,
    private val factory: BufferFactory,
    private val counts: ConnCounts,
) {
    private val spare = ArrayDeque<Slot>()
    private val lentOut = ArrayDeque<Slot>()
    private val waiting = ArrayDeque<DatagramWaiter>()
    private var reader: BatchReader = BatchReader.Away
    private val arrivals = arrivalsOf(incoming)

    /** How hard arrivals pressed on quiche's queue, for a receiver that drops. */
    val pressure = DatagramPressure()

    private val tally = DatagramTally(q, counts, pressure)

    /** Answers [waiter] with the oldest datagram quiche holds that is still fresh at [now], else parks it until one arrives. */
    fun receive(
        waiter: DatagramWaiter,
        now: Nanos,
    ): Receipt =
        when (pull(waiter, now)) {
            Pulled.Got -> Receipt.Delivered
            Pulled.Nothing -> {
                waiting.addLast(waiter)
                Receipt.Parked
            }
        }

    fun withdraw(waiter: DatagramWaiter) {
        waiting.remove(waiter)
    }

    /**
     * Gives back what the last batch was lent, then lends [into] the oldest datagrams quiche holds that are still fresh at
     * [now], at most [LEND_WINDOW], else parks it until one arrives.
     */
    fun lend(
        into: BatchWaiter,
        now: Nanos,
    ): Receipt {
        while (lentOut.isNotEmpty()) spare.addLast(lentOut.removeFirst())
        var left = held(now)
        while (left > 0 && lentOut.size < LEND_WINDOW) {
            left--
            val slot =
                if (spare.isNotEmpty()) {
                    spare.removeFirst()
                } else {
                    when (val fresh = made()) {
                        is Made.Fresh -> fresh.slot
                        is Made.Unaddressable -> break
                    }
                }
            val length =
                q.api.connDgramRecvStep(q.handle, slot.bytes.address, slot.bytes.capacity).fold(
                    more = { it },
                    last = { it },
                    nothingYet = {
                        spare.addFirst(slot)
                        break
                    },
                    gone = {
                        spare.addFirst(slot)
                        break
                    },
                    reset = {
                        spare.addFirst(slot)
                        break
                    },
                    error = {
                        spare.addFirst(slot)
                        break
                    },
                )
            tally.pulled()
            when (arrivals.taken(now)) {
                Expiry.Expired -> {
                    spare.addFirst(slot)
                    counts.datagramExpiredIncoming()
                }
                Expiry.Fresh -> {
                    lentOut.addLast(slot)
                    into.lent(slot.bytes.owner, length)
                }
            }
        }
        if (lentOut.isNotEmpty()) {
            reader = BatchReader.Away
            pressure.lent(lentOut.size)
            return Receipt.Delivered
        }
        reader = BatchReader.Parked(into)
        return Receipt.Parked
    }

    /** The batch [into] no longer waits. */
    fun withdraw(into: BatchWaiter) {
        when (val parked = reader) {
            is BatchReader.Parked -> if (parked.waiter === into) reader = BatchReader.Away
            is BatchReader.Away, is BatchReader.Told -> Unit
        }
    }

    /** The datagram slots the box holds, spare and lent: [DATAGRAM_ROOM] bytes each. */
    fun slotsHeld(): Int = spare.size + lentOut.size

    /**
     * Run after every read quiche takes, at its time [now]: the receives parked are answered from quiche, and a parked
     * batch is told datagrams arrived, so it is lent them when it runs again, after the pass's reads.
     */
    fun drain(now: Nanos) {
        when (val parked = reader) {
            is BatchReader.Parked ->
                if (q.api.hasReadableDgram(q.handle)) {
                    reader = BatchReader.Told
                    parked.waiter.arrived()
                }
            is BatchReader.Away, is BatchReader.Told -> Unit
        }
        when (arrivals) {
            is DatagramArrivals.Stamped -> held(now)
            is DatagramArrivals.Unstamped -> Unit
        }
        while (waiting.isNotEmpty()) {
            val first = waiting.first()
            when (first.interest()) {
                Interest.Withdrawn -> {
                    waiting.removeFirst()
                    first.discard()
                }
                Interest.Wanted ->
                    when (pull(first, now)) {
                        Pulled.Got -> waiting.removeFirst()
                        Pulled.Nothing -> break
                    }
            }
        }
    }

    /** Counts what quiche dropped as of [read], a statistics read made inside the connection just now. */
    fun counted(read: QuicStatsRead) =
        when (read) {
            is QuicStatsRead.Read -> tally.count(read.stats.datagramsReceived, q.api.connDgramRecvQueueLen(q.handle), stood())
            is QuicStatsRead.Ended -> Unit
        }

    /** The connection ended: what quiche dropped is counted, every parked receive is told, and the slots' memory goes. */
    fun endAll(end: ConnEnd) {
        tally.fromQuiche(q.api.connDgramRecvQueueLen(q.handle), stood())
        while (waiting.isNotEmpty()) waiting.removeFirst().connectionEnded(end)
        when (val parked = reader) {
            is BatchReader.Parked -> {
                reader = BatchReader.Away
                parked.waiter.connectionEnded(end)
            }
            is BatchReader.Away, is BatchReader.Told -> Unit
        }
        for (held in listOf(spare, lentOut)) {
            held.forEach { it.bytes.free() }
            held.clear()
        }
    }

    /**
     * How many datagrams quiche holds, read at [now]. A full queue may have dropped some, which are counted; an age bound
     * stamps the ones that arrived since it was last read with [now].
     */
    private fun held(now: Nanos): Int {
        val held = q.api.connDgramRecvQueueLen(q.handle)
        val drops = if (held >= bound) tally.fromQuiche(held, stood()) else 0L
        arrivals.read(held, drops, now)
        pressure.held(held)
        return held
    }

    /** Where the batch reader stands, as a drop counted now is told. */
    private fun stood(): ReaderStood =
        when (reader) {
            is BatchReader.Away -> ReaderStood.Away
            is BatchReader.Parked -> ReaderStood.Parked
            is BatchReader.Told -> ReaderStood.Told
        }

    /** Reads quiche's oldest datagram fresh at [now] into [waiter]'s memory, passing over the expired. */
    private fun pull(
        waiter: DatagramWaiter,
        now: Nanos,
    ): Pulled {
        while (true) {
            val length =
                q.api.connDgramRecvStep(q.handle, waiter.address, waiter.capacity).fold(
                    more = { it },
                    last = { it },
                    nothingYet = { return Pulled.Nothing },
                    gone = { return Pulled.Nothing },
                    reset = { return Pulled.Nothing },
                    error = { code ->
                        waiter.refused(code)
                        return Pulled.Got
                    },
                )
            tally.pulled()
            when (arrivals.taken(now)) {
                Expiry.Expired -> counts.datagramExpiredIncoming()
                Expiry.Fresh -> {
                    waiter.arrived(length)
                    return Pulled.Got
                }
            }
        }
    }

    /** A new slot from the connection's factory, which the connect checked makes addressable memory. */
    private fun made(): Made {
        val buffer = factory.allocate(DATAGRAM_ROOM)
        return when (val memory = NativeBytes.of(buffer)) {
            is Addressed.Native -> Made.Fresh(Slot(memory.bytes))
            is Addressed.NotAddressable -> {
                buffer.freeNativeMemory()
                Made.Unaddressable
            }
        }
    }

    private sealed interface Made {
        class Fresh(
            val slot: Slot,
        ) : Made

        data object Unaddressable : Made
    }

    private enum class Pulled { Got, Nothing }
}
