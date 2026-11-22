@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.NO_DATAGRAM_ROOM
import com.ditchoom.socket.quic.QUICHE_ERR_INVALID_STATE
import com.ditchoom.socket.quic.QuicheSendStop
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.stream.Backlog
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.time.since
import kotlinx.coroutines.CancellableContinuation
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.jvm.JvmInline

/** What a feed did: moved datagrams into quiche, moved none, or met a quiche refusal that ends the connection. */
internal sealed interface Refill {
    data object Moved : Refill

    data object Nothing : Refill

    /** quiche refused a datagram with [code], which no live connection answers. */
    class Refused(
        val code: Int,
    ) : Refill
}

/** What quiche did with one datagram handed to it. */
private sealed interface HandOff {
    data object Took : HandOff

    /** quiche's queue is full, or it has no path to size a datagram for: the datagram waits. */
    data object Wait : HandOff

    data object Dropped : HandOff

    class Refused(
        val code: Int,
    ) : HandOff
}

/** Whether quiche's datagram queue was full when last asked, and has not written a packet since. */
private enum class QuicheQueue { MayHaveRoom, Full }

/** Whether a connection's datagrams are negotiated. */
internal enum class Negotiation { Negotiated, None }

/** The bytes one datagram may carry, as quiche last said, or none when the connection carries no datagram. */
@JvmInline
internal value class DatagramRoom(
    private val bytes: Int,
) {
    fun negotiation(): Negotiation = if (bytes == NO_DATAGRAM_ROOM) Negotiation.None else Negotiation.Negotiated

    /** The bytes one datagram may carry; 0 when none can be sent. */
    fun maxBytes(): Int = maxOf(bytes, 0)
}

/** A quiche refusal of a datagram that no live connection answers: what ends the connection. */
internal class DatagramRefusedByQuiche(
    val code: Int,
) : Throwable()

/** The connection's factory made datagram memory without an address, which the connect had checked it would not. */
internal class DatagramMemoryNotAddressable : Throwable()

/**
 * A connection's RFC 9221 sends, inside it. The [queue] any thread offers to is bounded so that it, the door and quiche's
 * short hand-off behind them together hold at most the options' depth; it is fed into quiche from the head each time
 * the connection writes and each time the write runs dry. A datagram past the outgoing age at the head is dropped and
 * counted; the one quiche refused last for a full queue waits at the door, outside the queue's bound, and goes first
 * next time, still bound by the age. Callers waiting for room are woken in the order they began to wait once the queue
 * is at its low-water mark. The memory of slots nobody needed for a while is let go.
 */
@Confined
internal class DatagramSender(
    private val q: QuicheConnRef,
    val queue: DatagramQueue,
    private val outgoing: AgeBound,
    private val counts: ConnCounts,
) {
    private val room = AtomicInt(NO_DATAGRAM_ROOM)
    private val waiting = ArrayDeque<RoomWaiter>()
    private var door: Head = Head.Empty
    private var quiche = QuicheQueue.MayHaveRoom
    private var peak = 0
    private var lastPeak = 0
    private var window = Nanos(0L)

    /** Monotonic: datagrams quiche took to send. */
    var output = 0L
        private set

    /** Any thread: the room a datagram has, as of the connection's last turn. */
    @Published
    fun room(): DatagramRoom = DatagramRoom(room.load())

    /** Reads the room a datagram has now. */
    fun readRoom() = room.store(q.api.connDgramMaxWritableBytes(q.handle))

    /** Whether datagrams wait to be sent, in the queue or at the door. */
    fun backlog(): Backlog =
        when (door) {
            is DatagramSlot -> Backlog.Waiting
            is Head.Empty -> if (queue.size() > 0) Backlog.Waiting else Backlog.None
        }

    /** quiche wrote a packet, which may have carried datagrams out of its queue. */
    fun quicheWrote() {
        quiche = QuicheQueue.MayHaveRoom
    }

    /**
     * Moves datagrams from the queue's head into quiche until quiche's queue is full or this one is empty, at [now]. A
     * quiche that was full and has written nothing since is not asked again.
     */
    fun feed(now: Nanos): Refill {
        wakeIfLow()
        if (queue.life() is QueueLife.Closed || quiche == QuicheQueue.Full) return Refill.Nothing
        peak = maxOf(peak, queue.size())
        var moved: Refill = Refill.Nothing
        when (val held = door) {
            is Head.Empty -> Unit
            is DatagramSlot ->
                when (val handed = handOff(held, now)) {
                    is HandOff.Took -> moved = Refill.Moved.also { leaveDoor(held) }
                    is HandOff.Dropped -> leaveDoor(held)
                    is HandOff.Wait -> return moved.also { quiche = QuicheQueue.Full }
                    is HandOff.Refused -> return Refill.Refused(handed.code).also { leaveDoor(held) }
                }
        }
        while (true) {
            val slot =
                when (val head = queue.take()) {
                    is Head.Empty -> break
                    is DatagramSlot -> head
                }
            when (val handed = handOff(slot, now)) {
                is HandOff.Took -> moved = Refill.Moved.also { queue.done(slot) }
                is HandOff.Dropped -> queue.done(slot)
                is HandOff.Wait -> {
                    door = slot
                    quiche = QuicheQueue.Full
                    break
                }
                is HandOff.Refused -> return Refill.Refused(handed.code).also { queue.done(slot) }
            }
        }
        wakeIfLow()
        return moved
    }

    /** At [now], once a turn: lets go of the memory of free slots beyond twice what the last two windows needed. */
    fun trim(now: Nanos) {
        peak = maxOf(peak, queue.size())
        if (now.since(window) < TRIM_WINDOW) return
        queue.trim(maxOf(MIN_KEPT, 2 * maxOf(peak, lastPeak)))
        lastPeak = peak
        peak = queue.size()
        window = now
    }

    /** [wait] is answered once the queue is at its low-water mark: now, or when a feed finds it there. */
    fun awaitRoom(wait: RoomWaiter) {
        if (queue.size() <= queue.lowWater || queue.life() is QueueLife.Closed) wait.room() else waiting.addLast(wait)
    }

    fun withdraw(wait: RoomWaiter) {
        waiting.remove(wait)
    }

    /**
     * The connection reached its close point, or ended, with [end]: nothing more goes to quiche; what is queued, at the
     * door and in quiche's own queue is dropped and counted, and every waiting caller is answered.
     */
    fun close(end: ConnEnd) {
        when (val held = door) {
            is Head.Empty -> Unit
            is DatagramSlot -> {
                counts.datagramDroppedAtClose()
                leaveDoor(held)
            }
        }
        when (queue.close(end)) {
            Closing.Now -> counts.datagramsDroppedAtClose(q.api.connDgramPurgeOutgoing(q.handle))
            Closing.Already -> Unit
        }
        while (waiting.isNotEmpty()) waiting.removeFirst().room()
    }

    private fun leaveDoor(slot: DatagramSlot) {
        door = Head.Empty
        queue.done(slot)
    }

    private fun wakeIfLow() {
        if (waiting.isEmpty() || queue.size() > queue.lowWater) return
        while (waiting.isNotEmpty()) waiting.removeFirst().room()
    }

    private fun handOff(
        slot: DatagramSlot,
        now: Nanos,
    ): HandOff {
        if (outgoing.expired(slot.queuedAt, now) == Expiry.Expired) {
            counts.datagramExpiredOutgoing()
            return HandOff.Dropped
        }
        val address =
            when (val memory = slot.memory) {
                is SlotMemory.Made -> memory.bytes.address
                is SlotMemory.Unmade -> return HandOff.Dropped.also { counts.datagramDroppedUnsendable() }
            }
        val sent = q.api.connDgramSend(q.handle, address, slot.length)
        return when {
            sent >= 0 -> {
                output++
                HandOff.Took
            }
            sent == QUICHE_ERR_DONE || sent == QUICHE_ERR_INVALID_STATE -> HandOff.Wait
            sent == QuicheSendStop.QUICHE_ERR_BUFFER_TOO_SHORT -> {
                readRoom()
                counts.datagramDroppedUnsendable()
                HandOff.Dropped
            }
            else -> HandOff.Refused(sent).also { counts.datagramDroppedUnsendable() }
        }
    }

    private companion object {
        val TRIM_WINDOW = Span(1_000_000_000L)
        const val MIN_KEPT = 4
    }
}

/** The datagrams a write may feed: the connection's while it runs; past its close point none, and what waited is dropped. */
internal fun ConnParts.datagramsToWrite(): Datagrams =
    when (val now = life.view()) {
        is Life.Running -> slots.datagrams
        is Life.Closing -> slots.datagrams.closed(now.end)
        is Life.Over -> Datagrams.Off
    }

/** A caller parked inside the connection until its send queue is at its low-water mark or closed. Answered once. */
internal interface RoomWaiter {
    /** The caller goes on. */
    fun room()
}

/**
 * A caller waiting in [DatagramSend.NotSent.QueueFull.awaitRoom][com.ditchoom.socket.quic.DatagramSend.NotSent.QueueFull.awaitRoom],
 * parked inside the connection until its send queue is at its low-water mark or closed. Answered once.
 */
internal class RoomWait(
    private val caller: CancellableContinuation<Unit>,
) : ConnWork(),
    RoomWaiter {
    override fun run(inside: Inside) =
        when (val datagrams = inside.datagrams) {
            is Datagrams.Off -> room()
            is Datagrams.On -> datagrams.out.awaitRoom(this)
        }

    override fun gone(end: ConnEnd) = room()

    /** The caller goes on; one cancelled meanwhile is not resumed. */
    override fun room() = caller.resume(Unit)
}

/** Takes back a [RoomWait] whose caller was cancelled. */
internal class RoomWithdraw(
    private val wait: RoomWait,
) : ConnWork() {
    override fun run(inside: Inside) =
        when (val datagrams = inside.datagrams) {
            is Datagrams.Off -> Unit
            is Datagrams.On -> datagrams.out.withdraw(wait)
        }

    override fun gone(end: ConnEnd) = Unit
}

/** Has the connection served after a send queued into an idle queue: its next write feeds quiche. One per queue, reused. */
internal class FeedWork(
    private val queue: DatagramQueue,
) : ConnWork() {
    override fun run(inside: Inside) = queue.fed()

    override fun gone(end: ConnEnd) = queue.fed()
}
