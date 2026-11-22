@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.time.LoopClock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/** What [DatagramQueue.offer] did with a datagram; a closed queue answers with its [QueueLife.Closed]. */
internal sealed interface Offered {
    data object Queued : Offered

    /** The queue is at its bound and refuses new datagrams, or no cell or slot could be had; nothing was queued. */
    data object Full : Offered

    /** The connection's factory made memory without an address; nothing was queued. */
    data object Unaddressable : Offered
}

/** Whether the queue takes datagrams, or was closed at the connection's close point with how it ended. */
internal sealed interface QueueLife {
    data object Open : QueueLife

    class Closed(
        val end: ConnEnd,
    ) : QueueLife,
        Offered
}

/** Whether an offer must hand the connection the queue's feed, or it is handed already. */
internal enum class Feed { Hand, Handed }

/** The ring and slots of a queue, made when its first datagram is offered. */
internal sealed interface QueueParts {
    data object Unmade : QueueParts

    class Made(
        val ring: DatagramRing,
        val slots: DatagramSlots,
    ) : QueueParts
}

/**
 * A connection's RFC 9221 send queue: any thread offers, and the connection takes from the head. At most [bound]
 * datagrams are admitted, counted from admission until the connection is done with them; past it a new datagram is
 * refused, or under [Overflow.EvictOldest] the oldest queued one is dropped, counted, and its place goes to the new one.
 * Each datagram is copied into a slot borrowed before it is admitted, so no allocation happens while a cell is claimed;
 * the slots and the ring are made at the first offer, so a connection that never sends holds neither. Closed at the
 * connection's close point; its memory goes once no offer is inside.
 */
@Queue
internal class DatagramQueue(
    private val bound: Int,
    private val overflow: Overflow,
    private val outgoing: AgeBound,
    private val factory: BufferFactory,
    private val clock: LoopClock,
    private val counts: ConnCounts,
    /** Slots beyond the bound, for senders that hold one while their datagram is being admitted. */
    private val slotSlack: Int = SLOT_SLACK,
) {
    private val parts = AtomicReference<QueueParts>(QueueParts.Unmade)
    private val life = AtomicReference<QueueLife>(QueueLife.Open)
    private val admitted = AtomicInt(0)

    /** Offers inside, and the closed mark: whoever leaves a closed queue last, or closes an empty one, drops it. */
    private val inside = AtomicInt(0)
    private val freed = AtomicInt(KEPT)
    private val feed = AtomicInt(IDLE)

    /** The work that feeds this queue's datagrams to quiche; in at most one inbox at a time, by [wantsFeed]. */
    val feedWork = FeedWork(this)

    /** Datagrams admitted at or below which senders waiting for room are woken. */
    val lowWater: Int = bound / LOW_WATER_DIVISOR

    /** Any thread: datagrams admitted and not yet done with; stale by the time it returns. */
    fun size(): Int = admitted.load()

    fun life(): QueueLife = life.load()

    /** The ring and slots, or [QueueParts.Unmade] before the first offer. */
    fun parts(): QueueParts = parts.load()

    /**
     * Any thread: queues the [prefixLength] remaining bytes of [prefix] then the [length] remaining bytes of [payload]
     * as one datagram, copied once into a slot.
     */
    fun offer(
        prefix: ReadBuffer,
        prefixLength: Int,
        payload: ReadBuffer,
        length: Int,
    ): Offered {
        inside.incrementAndFetch()
        try {
            return when (val now = life.load()) {
                is QueueLife.Closed -> now
                is QueueLife.Open -> offerOpen(made(), prefix, prefixLength, payload, length)
            }
        } finally {
            leave()
        }
    }

    private fun offerOpen(
        made: QueueParts.Made,
        prefix: ReadBuffer,
        prefixLength: Int,
        payload: ReadBuffer,
        length: Int,
    ): Offered {
        if (overflow == Overflow.RefuseNew && admitted.load() >= bound) return Offered.Full
        val slot =
            when (val taken = made.slots.take()) {
                is DatagramSlot -> taken
                is SlotTake.None -> return Offered.Full
            }
        var admission = Admission.Full
        var placed = Put.Blocked
        try {
            admission = admit(made)
            when (admission) {
                Admission.In -> Unit
                Admission.Full -> return Offered.Full
            }
            when (slot.fill(factory, clock, outgoing, prefix, prefixLength, payload, length)) {
                Filled.Yes -> Unit
                Filled.Unaddressable -> return Offered.Unaddressable
            }
            placed = made.ring.put(slot)
            return when (placed) {
                Put.Placed -> Offered.Queued
                Put.Blocked -> Offered.Full
            }
        } finally {
            when (placed) {
                Put.Placed -> Unit
                Put.Blocked -> {
                    made.slots.give(slot)
                    if (admission == Admission.In) admitted.decrementAndFetch()
                }
            }
        }
    }

    /** A place among the [bound]: a free one, or under eviction the oldest queued datagram's, which is dropped. */
    private fun admit(made: QueueParts.Made): Admission {
        while (true) {
            val seen = admitted.load()
            if (seen < bound) {
                if (admitted.compareAndSet(seen, seen + 1)) return Admission.In
                continue
            }
            return when (overflow) {
                Overflow.RefuseNew -> Admission.Full
                Overflow.EvictOldest -> evictOldest(made)
            }
        }
    }

    /** Takes the oldest queued datagram's place for the new one; none may be published yet, which reads as full. */
    private fun evictOldest(made: QueueParts.Made): Admission {
        repeat(PATIENCE) {
            when (val oldest = made.ring.take()) {
                is DatagramSlot -> {
                    made.slots.give(oldest)
                    counts.datagramEvicted()
                    return Admission.In
                }
                is Head.Empty -> Unit
            }
        }
        return Admission.Full
    }

    /** The connection: the oldest queued datagram, which is its own until [done]. */
    fun take(): Head =
        when (val now = parts.load()) {
            is QueueParts.Unmade -> Head.Empty
            is QueueParts.Made -> now.ring.take()
        }

    /** The connection is done with [slot]'s datagram: it left, or was dropped; its place and slot are free again. */
    fun done(slot: DatagramSlot) {
        when (val now = parts.load()) {
            is QueueParts.Unmade -> Unit
            is QueueParts.Made -> now.slots.give(slot)
        }
        admitted.decrementAndFetch()
    }

    /** The connection: frees the memory of free slots beyond [keep]. */
    fun trim(keep: Int) =
        when (val now = parts.load()) {
            is QueueParts.Unmade -> Unit
            is QueueParts.Made -> now.slots.trim(keep)
        }

    /** Any thread, after an offer: [Feed.Hand] when the connection must be handed [feedWork], once until it runs. */
    fun wantsFeed(): Feed = if (feed.compareAndSet(IDLE, QUEUED)) Feed.Hand else Feed.Handed

    /** Inside: [feedWork] runs, so the next offer hands it again. */
    fun fed() = feed.store(IDLE)

    /**
     * The connection, at its close point with [end]: no offer is taken any more, and what is queued is dropped, counted,
     * by whoever leaves last, here or an offer still inside; then the memory goes.
     */
    fun close(end: ConnEnd) {
        if (!life.compareAndSet(QueueLife.Open, QueueLife.Closed(end))) return
        while (true) {
            val seen = inside.load()
            if (inside.compareAndSet(seen, seen or CLOSED)) {
                if (seen == 0) drop()
                return
            }
        }
    }

    /** An offer leaves; the last one out of a closed queue drops it. The count and the closed mark are one word. */
    private fun leave() {
        if (inside.decrementAndFetch() == CLOSED) drop()
    }

    /** Once, after the close with nobody inside: the queued datagrams are counted as dropped, and every slot's memory goes. */
    private fun drop() {
        if (!freed.compareAndSet(KEPT, FREED)) return
        when (val now = parts.load()) {
            is QueueParts.Unmade -> Unit
            is QueueParts.Made -> {
                while (true) {
                    when (now.ring.take()) {
                        is DatagramSlot -> counts.datagramDroppedAtClose()
                        is Head.Empty -> break
                    }
                }
                now.slots.freeAll()
            }
        }
    }

    private fun made(): QueueParts.Made {
        when (val now = parts.load()) {
            is QueueParts.Made -> return now
            is QueueParts.Unmade -> Unit
        }
        val fresh = QueueParts.Made(DatagramRing(bound * RING_LAPS), DatagramSlots(bound + slotSlack))
        return if (parts.compareAndSet(QueueParts.Unmade, fresh)) fresh else made()
    }

    private enum class Admission { In, Full }

    private companion object {
        const val CLOSED = 1 shl 30
        const val IDLE = 0
        const val QUEUED = 1
        const val KEPT = 0
        const val FREED = 1
        const val LOW_WATER_DIVISOR = 2
        const val PATIENCE = 64

        /** The ring is this many times the bound, so a put waits on a taker only a whole bound of datagrams behind it. */
        const val RING_LAPS = 2

        const val SLOT_SLACK = 64
    }
}
