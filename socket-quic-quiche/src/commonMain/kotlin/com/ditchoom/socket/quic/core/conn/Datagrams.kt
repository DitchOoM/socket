package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.stream.Backlog
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** The room a received datagram is read into: more than any datagram the endpoint's sockets receive. */
internal const val DATAGRAM_ROOM = 1350

/** Whether a parked receive still wants a datagram. */
internal enum class Interest { Wanted, Withdrawn }

/** Where a receive stands after [DatagramBox.receive]: answered, or parked until a datagram arrives. */
internal enum class Receipt { Delivered, Parked }

/** A receive parked in the box. Runs inside the connection. */
internal interface DatagramWaiter {
    /** Where the next datagram is written. */
    val address: Long

    /** The room at [address]. */
    val capacity: Int

    fun interest(): Interest

    /** A datagram of [length] bytes is at [address]. */
    fun arrived(length: Int)

    /** quiche refused the receive with [code]. */
    fun refused(code: Int)

    /** The receive was withdrawn: its memory is let go. */
    fun discard()

    /** The connection ended with the receive still parked. */
    fun connectionEnded(end: ConnEnd)
}

/** A queued datagram: its bytes and length. */
@Confined
private class Queued(
    val bytes: NativeBytes,
) {
    var length = 0
        private set
    var arrivedAt = 0L
        private set

    fun filled(
        length: Int,
        at: Long,
    ) {
        this.length = length
        arrivedAt = at
    }
}

/**
 * A connection's received RFC 9221 datagrams, inside it only. Receives park here, one answered per arriving datagram,
 * written straight into the receive's own memory. A datagram that finds none parked is kept in a queue of at most
 * [bound] slots, made as they are needed and reused after; when it is full the oldest is dropped for the newest and
 * counted, so a receiver that stops reading holds a bounded amount of memory. One kept past [incoming] is dropped,
 * counted, when it would be delivered.
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
    private var made = 0

    /** Answers [waiter] with the oldest datagram held that is still fresh at [now] or quiche's next, else parks it until one arrives. */
    fun receive(
        waiter: DatagramWaiter,
        now: Nanos,
    ): Receipt {
        while (queued.isNotEmpty()) {
            val next = queued.removeFirst()
            spare.addLast(next)
            when (incoming.expired(next.arrivedAt, now.value)) {
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
    }

    /** The connection ended: every parked receive is told, and the queue's memory goes. */
    fun endAll(end: ConnEnd) {
        while (waiting.isNotEmpty()) waiting.removeFirst().connectionEnded(end)
        queued.forEach { it.bytes.free() }
        spare.forEach { it.bytes.free() }
        queued.clear()
        spare.clear()
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
                spare.isNotEmpty() -> spare.first()
                made < bound ->
                    when (val fresh = made()) {
                        is Made.Slot -> fresh.queued.also { spare.addLast(it) }
                        is Made.Unaddressable -> return Kept.None
                    }
                else -> queued.first()
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
        slot.filled(got, now.value)
        if (spare.isNotEmpty()) {
            spare.removeFirst()
        } else {
            queued.removeFirst()
            counts.datagramOverflowed()
        }
        queued.addLast(slot)
        return Kept.One
    }

    /** A new slot from the connection's factory, which the connect checked makes addressable memory. */
    private fun made(): Made {
        val buffer = factory.allocate(DATAGRAM_ROOM)
        return when (val memory = NativeBytes.of(buffer)) {
            is Addressed.Native -> {
                made++
                Made.Slot(Queued(memory.bytes))
            }
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

/** Inside, at [now]: the datagram work of an established connection, served each turn: the room is read, what quiche received moves on, and idle send memory is let go. */
internal fun Datagrams.wake(now: Nanos) =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> {
            out.readRoom()
            box.drain(now)
            out.trim(now)
        }
    }

/** Inside: quiche has read a datagram, at [now]. */
internal fun Datagrams.received(now: Nanos) =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> box.drain(now)
    }

/** Inside, as a write begins or its output runs dry at [now]: queued sends go to quiche, which may then have more to write. */
internal fun Datagrams.refill(now: Nanos): Refill =
    when (this) {
        is Datagrams.Off -> Refill.Nothing
        is Datagrams.On -> out.feed(now)
    }

/** Inside: quiche wrote a packet, which may have taken datagrams out of its queue. */
internal fun Datagrams.quicheWrote() =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> out.quicheWrote()
    }

/** Whether datagrams wait to be sent. */
internal fun Datagrams.backlog(): Backlog =
    when (this) {
        is Datagrams.Off -> Backlog.None
        is Datagrams.On -> out.backlog()
    }

/** Inside: the connection's close point, with [end]: nothing more is sent; none are left to write. */
internal fun Datagrams.closed(end: ConnEnd): Datagrams {
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> out.close(end)
    }
    return Datagrams.Off
}

/** Inside: the connection ended with [end]. */
internal fun Datagrams.endAll(end: ConnEnd) =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> {
            out.close(end)
            box.endAll(end)
        }
    }

/** Any thread: the bytes one datagram may carry as of the connection's last turn, or 0. */
internal fun Datagrams.maxBytes(): Int =
    when (this) {
        is Datagrams.Off -> 0
        is Datagrams.On -> out.room().maxBytes()
    }
