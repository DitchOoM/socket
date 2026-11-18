@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.NO_DATAGRAM_ROOM
import com.ditchoom.socket.quic.QUICHE_ERR_INVALID_STATE
import com.ditchoom.socket.quic.QuicheSendStop
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The room a received datagram is read into: more than any datagram the endpoint's sockets receive. */
internal const val DATAGRAM_ROOM = 1350

/** What one datagram send came to. The answers that carry nothing are made once. */
internal sealed interface DatagramSend {
    data object Sent : DatagramSend

    /** Datagrams are off here, or the peer never advertised support for them. */
    data object NotNegotiated : DatagramSend

    /** quiche's send queue is full; nothing was queued. */
    data object QueueFull : DatagramSend

    /** The datagram does not fit the path as it stands: [maxBytes] is what fits. */
    class TooLarge(
        val maxBytes: Int,
    ) : DatagramSend

    /** quiche refused the datagram with [code]. */
    class Refused(
        val code: Int,
    ) : DatagramSend
}

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

    fun filled(length: Int) {
        this.length = length
    }
}

/**
 * A connection's RFC 9221 datagrams, inside it only: the only file that sends or receives them in quiche. Receives
 * park here, one answered per arriving datagram, written straight into the receive's own memory. A datagram that finds
 * none parked is kept in a queue of at most [bound] slots, made as they are needed and reused after; when it is full the
 * oldest is dropped for the newest and counted, so a receiver that stops reading holds a bounded amount of memory.
 */
@Confined
internal class DatagramBox(
    private val q: QuicheConnRef,
    private val bound: Int,
    private val factory: BufferFactory,
    private val counts: ConnCounts,
) {
    private val queued = ArrayDeque<Queued>()
    private val spare = ArrayDeque<Queued>()
    private val waiting = ArrayDeque<DatagramWaiter>()
    private var made = 0
    private val room = AtomicInt(NO_DATAGRAM_ROOM)

    /** Monotonic: datagrams quiche took to send. */
    var output = 0L
        private set

    /** Any thread: the bytes one datagram may carry, as of the connection's last turn; 0 when none can be sent. */
    @Published
    fun maxBytes(): Int = maxOf(room.load(), 0)

    /** Sends the [length] bytes at [address] as one datagram: whole, or refused with nothing queued. */
    fun send(
        address: Long,
        length: Int,
    ): DatagramSend {
        val max = q.api.connDgramMaxWritableBytes(q.handle)
        room.store(max)
        if (max == NO_DATAGRAM_ROOM) return DatagramSend.NotNegotiated
        if (length > max) return DatagramSend.TooLarge(max)
        val sent = q.api.connDgramSend(q.handle, if (length > 0) address else 0L, length)
        return when {
            sent >= 0 -> {
                output++
                DatagramSend.Sent
            }
            sent == QUICHE_ERR_DONE -> DatagramSend.QueueFull
            sent == QUICHE_ERR_INVALID_STATE -> DatagramSend.NotNegotiated
            sent == QuicheSendStop.QUICHE_ERR_BUFFER_TOO_SHORT -> DatagramSend.TooLarge(max)
            else -> DatagramSend.Refused(sent)
        }
    }

    /** Answers [waiter] with the oldest datagram held or quiche's next, else parks it until one arrives. */
    fun receive(waiter: DatagramWaiter): Receipt {
        if (queued.isNotEmpty()) {
            val next = queued.removeFirst()
            next.bytes.copyOut(waiter.address, next.length)
            waiter.arrived(next.length)
            spare.addLast(next)
            return Receipt.Delivered
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

    /** Reads the room a datagram has now, and moves what quiche holds on. */
    fun wake() {
        room.store(q.api.connDgramMaxWritableBytes(q.handle))
        drain()
    }

    /**
     * Moves what quiche holds to the receives parked, else into the queue. Run after every datagram quiche reads, so
     * quiche's own queue never holds more than that one and this queue's bound is the only one that drops.
     */
    fun drain() {
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
        while (keep() == Kept.One) Unit
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
    private fun keep(): Kept {
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
        slot.filled(got)
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

/** Inside: the datagram work of an established connection, for each variant of its slot. */
internal fun Datagrams.wake() =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> box.wake()
    }

/** Inside: quiche has read a datagram. */
internal fun Datagrams.received() =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> box.drain()
    }

/** Inside: the connection ended with [end]. */
internal fun Datagrams.endAll(end: ConnEnd) =
    when (this) {
        is Datagrams.Off -> Unit
        is Datagrams.On -> box.endAll(end)
    }

/** Any thread: the bytes one datagram may carry as of the connection's last turn, or 0. */
internal fun Datagrams.maxBytes(): Int =
    when (this) {
        is Datagrams.Off -> 0
        is Datagrams.On -> box.maxBytes()
    }

