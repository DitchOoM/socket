@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.NO_DATAGRAM_ROOM
import com.ditchoom.socket.quic.QUICHE_ERR_INVALID_STATE
import com.ditchoom.socket.quic.QuicheSendStop
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.life.endOf
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.stream.Backlog
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.time.since
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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

/**
 * What is known of quiche's datagram queue: its length as last read or handed to since ([Counted]), that it wrote a packet
 * since, which may have carried datagrams out of it ([Wrote]), or that it was full when last asked and has written
 * nothing since ([Full]).
 */
private enum class QuicheQueue { Counted, Wrote, Full }

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

/**
 * A connection's RFC 9221 sends, inside it. The [queue] any thread offers to bounds every datagram on the send path at
 * the options' depth: those queued, the one at the door, and those in quiche's short hand-off, which hold their places
 * until quiche's queue no longer has them. It is fed into quiche from the head each time the connection writes and each
 * time the write runs dry. A datagram past the outgoing age at the head is dropped and counted; the one quiche refused
 * last for a full queue waits at the door and goes first next time, still bound by the age. Callers waiting for room
 * are woken in the order they began to wait once the send path is at its low-water mark. The memory of slots nobody
 * needed for a while is let go.
 */
@Confined
internal class DatagramSender(
    private val q: QuicheConnRef,
    val queue: DatagramQueue,
    private val outgoing: AgeBound,
    private val counts: ConnCounts,
) {
    private val room = AtomicInt(NO_DATAGRAM_ROOM)
    private val waiting = ArrayDeque<RoomWait>()
    private var door: Head = Head.Empty
    private var quiche = QuicheQueue.Counted

    /** Datagrams handed to quiche that its queue held when last asked, or that were handed since. */
    private var inQuiche = 0
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
            is Head.Empty -> if (queue.size() > inQuiche) Backlog.Waiting else Backlog.None
        }

    /** quiche wrote a packet, which may have carried datagrams out of its queue. */
    fun quicheWrote() {
        quiche = QuicheQueue.Wrote
    }

    /**
     * Moves datagrams from the queue's head into quiche until quiche's queue is full or this one is empty, at [now]. A
     * quiche that was full and has written nothing since is not asked again.
     */
    fun feed(now: Nanos): Refill {
        settle()
        wakeIfLow()
        if (queue.life() is QueueLife.Closed || quiche == QuicheQueue.Full) return Refill.Nothing
        peak = maxOf(peak, queue.size())
        var moved: Refill = Refill.Nothing
        when (val held = door) {
            is Head.Empty -> Unit
            is DatagramSlot ->
                when (val handed = handOff(held, now)) {
                    is HandOff.Took -> moved = Refill.Moved.also { leaveDoor(held) }
                    is HandOff.Dropped -> dropDoor(held)
                    is HandOff.Wait -> return moved.also { quiche = QuicheQueue.Full }
                    is HandOff.Refused -> return Refill.Refused(handed.code).also { dropDoor(held) }
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
                is HandOff.Dropped -> drop(slot)
                is HandOff.Wait -> {
                    door = slot
                    quiche = QuicheQueue.Full
                    break
                }
                is HandOff.Refused -> return Refill.Refused(handed.code).also { drop(slot) }
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
    fun awaitRoom(wait: RoomWait) {
        if (queue.size() <= queue.lowWater || queue.life() is QueueLife.Closed) wait.room() else waiting.addLast(wait)
    }

    fun withdraw(wait: RoomWait) {
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
                dropDoor(held)
            }
        }
        when (queue.close(end)) {
            Closing.Now -> {
                counts.datagramsDroppedAtClose(q.api.connDgramPurgeOutgoing(q.handle))
                queue.left(inQuiche)
                inQuiche = 0
            }
            Closing.Already -> Unit
        }
        while (waiting.isNotEmpty()) waiting.removeFirst().room()
    }

    private fun leaveDoor(slot: DatagramSlot) {
        door = Head.Empty
        queue.done(slot)
    }

    /** The door's datagram leaves the send path unsent. */
    private fun dropDoor(slot: DatagramSlot) {
        leaveDoor(slot)
        queue.left(1)
    }

    /** A taken datagram leaves the send path unsent. */
    private fun drop(slot: DatagramSlot) {
        queue.done(slot)
        queue.left(1)
    }

    /** After quiche wrote: frees the places of the datagrams its queue no longer holds, sent or dropped by quiche. */
    private fun settle() {
        if (quiche != QuicheQueue.Wrote) return
        quiche = QuicheQueue.Counted
        if (inQuiche == 0) return
        val holds = q.api.connDgramSendQueueLen(q.handle).coerceIn(0, inQuiche)
        queue.left(inQuiche - holds)
        inQuiche = holds
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
                inQuiche++
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

/** The sender of a connection whose datagrams go as this rule says: its queue bounds the whole send path at the rule's depth. */
internal fun DatagramRule.On.sender(
    q: QuicheConnRef,
    factory: BufferFactory,
    clock: LoopClock,
    counts: ConnCounts,
): DatagramSender = DatagramSender(q, DatagramQueue(sendBound, overflow, factory, clock, counts), outgoing, counts)

/** The datagrams a write may feed: the connection's while it runs; past its close point none, and what waited is dropped. */
internal fun ConnParts.datagramsToWrite(): Datagrams =
    when (val now = life.view()) {
        is Life.Running -> slots.datagrams
        is Life.Closing -> slots.datagrams.closed(endOf(now))
        is Life.Over -> Datagrams.Off
    }
