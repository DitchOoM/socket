package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** A queued datagram: its bytes and length. */
@Confined
private class Queued(
    val bytes: NativeBytes,
) {
    var length = 0
        private set
    var arrivedAt = Nanos(0L)
        private set

    fun filled(
        length: Int,
        at: Nanos,
    ) {
        this.length = length
        arrivedAt = at
    }
}

/** A batch receive parked in the box: told once datagrams are held for it to take. Runs inside the connection. */
internal interface BatchWaiter {
    /** The datagram [length] bytes long in [buffer], lent to the batch from its first byte. */
    fun lent(
        buffer: PlatformBuffer,
        length: Int,
    )

    /** Datagrams arrived for the parked batch: it takes them by lending again. */
    fun arrived()

    /** The connection ended with the batch still parked. */
    fun connectionEnded(end: ConnEnd)
}

/** The batch receive the box tells when datagrams arrive: none, or the one parked. */
private sealed interface BatchWait {
    data object None : BatchWait

    class Parked(
        val waiter: BatchWaiter,
    ) : BatchWait
}

/**
 * A connection's received RFC 9221 datagrams, inside it only. Receives park here, one answered per arriving datagram,
 * written straight into the receive's own memory. A datagram that finds none parked is kept in a queue of at most
 * [bound] slots, made as they are needed and reused after; when it is full the oldest is dropped for the newest and
 * counted, so a receiver that stops reading holds a bounded amount of memory. One kept past [incoming] is dropped,
 * counted, when it would be delivered. A batch receive is lent the queue's own slots, read in place, and gives them
 * back as the next batch is lent: at most [bound] slots are queued and as many lent.
 */
@Confined
internal class DatagramBox(
    private val q: QuicheConnRef,
    private val bound: Int,
    private val incoming: AgeBound,
    private val factory: BufferFactory,
    private val counts: ConnCounts,
) {
    private val queued = ArrayDeque<Queued>()
    private val spare = ArrayDeque<Queued>()
    private val waiting = ArrayDeque<DatagramWaiter>()
    private val lentOut = ArrayDeque<Queued>()
    private var batch: BatchWait = BatchWait.None

    /** How hard arrivals pressed on the queue, for a receiver that drops. */
    val pressure = DatagramPressure()

    /** Answers [waiter] with the oldest datagram held that is still fresh at [now] or quiche's next, else parks it until one arrives. */
    fun receive(
        waiter: DatagramWaiter,
        now: Nanos,
    ): Receipt {
        while (queued.isNotEmpty()) {
            val next = queued.removeFirst()
            spare.addLast(next)
            when (incoming.expired(next.arrivedAt, now)) {
                Expiry.Expired -> counts.datagramExpiredIncoming()
                Expiry.Fresh -> {
                    next.bytes.copyOut(waiter.address, next.length)
                    waiter.arrived(next.length)
                    return Receipt.Delivered
                }
            }
        }
        return when (pull(waiter)) {
            Pulled.Got -> Receipt.Delivered
            Pulled.Nothing -> {
                waiting.addLast(waiter)
                Receipt.Parked
            }
        }
    }

    fun withdraw(waiter: DatagramWaiter) {
        waiting.remove(waiter)
    }

    /**
     * Gives back what the last batch was lent, then lends [into] every datagram held that is still fresh at [now], oldest
     * first, else parks it until one arrives.
     */
    fun lend(
        into: BatchWaiter,
        now: Nanos,
    ): Receipt {
        while (lentOut.isNotEmpty()) spare.addLast(lentOut.removeFirst())
        while (keep(now) == Kept.One) Unit
        while (queued.isNotEmpty()) {
            val next = queued.removeFirst()
            when (incoming.expired(next.arrivedAt, now)) {
                Expiry.Expired -> {
                    spare.addLast(next)
                    counts.datagramExpiredIncoming()
                }
                Expiry.Fresh -> {
                    lentOut.addLast(next)
                    into.lent(next.bytes.owner, next.length)
                }
            }
        }
        if (lentOut.isNotEmpty()) {
            pressure.lent()
            return Receipt.Delivered
        }
        batch = BatchWait.Parked(into)
        return Receipt.Parked
    }

    /** The batch [into] no longer waits. */
    fun withdraw(into: BatchWaiter) {
        when (val parked = batch) {
            is BatchWait.Parked -> if (parked.waiter === into) batch = BatchWait.None
            is BatchWait.None -> Unit
        }
    }

    /**
     * Moves what quiche holds to the receives parked, else into the queue. Run after every datagram quiche reads, so
     * quiche's own queue never holds more than that one and this queue's bound is the only one that drops. What is
     * kept is stamped with [now], when it arrived.
     */
    fun drain(now: Nanos) {
        while (waiting.isNotEmpty()) {
            val first = waiting.first()
            when (first.interest()) {
                Interest.Withdrawn -> {
                    waiting.removeFirst()
                    first.discard()
                }
                Interest.Wanted ->
                    when (pull(first)) {
                        Pulled.Got -> waiting.removeFirst()
                        Pulled.Nothing -> return
                    }
            }
        }
        while (keep(now) == Kept.One) Unit
        when (val parked = batch) {
            is BatchWait.Parked ->
                if (queued.isNotEmpty()) {
                    batch = BatchWait.None
                    parked.waiter.arrived()
                }
            is BatchWait.None -> Unit
        }
    }

    /** The connection ended: every parked receive is told, and the queue's memory goes, what is lent included. */
    fun endAll(end: ConnEnd) {
        while (waiting.isNotEmpty()) waiting.removeFirst().connectionEnded(end)
        when (val parked = batch) {
            is BatchWait.Parked -> {
                batch = BatchWait.None
                parked.waiter.connectionEnded(end)
            }
            is BatchWait.None -> Unit
        }
        for (held in listOf(queued, spare, lentOut)) {
            held.forEach { it.bytes.free() }
            held.clear()
        }
    }

    private fun pull(waiter: DatagramWaiter): Pulled =
        q.api.connDgramRecvStep(q.handle, waiter.address, waiter.capacity).fold(
            more = { length ->
                waiter.arrived(length)
                Pulled.Got
            },
            last = { length ->
                waiter.arrived(length)
                Pulled.Got
            },
            nothingYet = { Pulled.Nothing },
            gone = { Pulled.Nothing },
            reset = { Pulled.Nothing },
            error = { code ->
                waiter.refused(code)
                Pulled.Got
            },
        )

    /** Reads one datagram quiche holds into the queue, over the oldest when the queue is full. */
    private fun keep(now: Nanos): Kept {
        val slot =
            when {
                queued.size >= bound -> queued.first()
                spare.isNotEmpty() -> spare.first()
                else ->
                    when (val fresh = made()) {
                        is Made.Slot -> fresh.queued.also { spare.addLast(it) }
                        is Made.Unaddressable -> return Kept.None
                    }
            }
        val got =
            q.api.connDgramRecvStep(q.handle, slot.bytes.address, slot.bytes.capacity).fold(
                more = { it },
                last = { it },
                nothingYet = { return Kept.None },
                gone = { return Kept.None },
                reset = { return Kept.None },
                error = { return Kept.None },
            )
        slot.filled(got, now)
        if (queued.size >= bound) {
            queued.removeFirst()
            counts.datagramOverflowed()
            pressure.dropped(now)
        } else {
            spare.removeFirst()
        }
        queued.addLast(slot)
        pressure.queued(queued.size)
        return Kept.One
    }

    /** A new slot from the connection's factory, which the connect checked makes addressable memory. */
    private fun made(): Made {
        val buffer = factory.allocate(DATAGRAM_ROOM)
        return when (val memory = NativeBytes.of(buffer)) {
            is Addressed.Native -> Made.Slot(Queued(memory.bytes))
            is Addressed.NotAddressable -> {
                buffer.freeNativeMemory()
                Made.Unaddressable
            }
        }
    }

    private sealed interface Made {
        class Slot(
            val queued: Queued,
        ) : Made

        data object Unaddressable : Made
    }

    private enum class Pulled { Got, Nothing }

    private enum class Kept { One, None }
}
