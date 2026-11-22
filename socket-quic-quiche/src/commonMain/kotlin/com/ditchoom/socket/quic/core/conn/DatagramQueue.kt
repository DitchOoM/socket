@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch

/** What [DatagramQueue.offer] did with a datagram. */
internal enum class Offered {
    Queued,

    /** The queue is at its depth and refuses new datagrams; nothing was queued. */
    Full,

    /** The connection ended: the queue takes nothing more. */
    Closed,

    /** The connection's factory made memory without an address; nothing was queued. */
    Unaddressable,
}

/** Whether an offer must hand the connection the queue's feed, or it is handed already. */
internal enum class Feed { Hand, Handed }

/** What [DatagramQueue.take] found at the head: a [QueuedDatagram], which is its own answer, or [Empty]. */
internal sealed interface Head {
    data object Empty : Head
}

/** A cell's memory: made the first time a datagram needs it, and kept for every later one. */
internal sealed interface CellMemory {
    data object Unmade : CellMemory

    class Made(
        val bytes: NativeBytes,
    ) : CellMemory
}

/** Whether a published cell carries a datagram, or nothing because no memory could be made for it. */
internal enum class Fill { Datagram, Nothing }

/**
 * One cell of a [DatagramQueue]: written only by the thread that claimed it, published by its sequence. The sequence is
 * the queue position the cell is free for, one past it once filled, and that plus the depth once taken.
 */
@Queue
internal class QueuedDatagram(
    first: Int,
) : Head {
    private val sequence = AtomicLong(first.toLong())
    var memory: CellMemory = CellMemory.Unmade
        private set
    var length = 0
        private set
    var queuedAt = 0L
        private set
    var fill = Fill.Nothing
        private set
    var claimed = 0L
        private set

    fun sequence(): Long = sequence.load()

    fun publish(sequence: Long) = this.sequence.store(sequence)

    fun claim(position: Long) {
        claimed = position
    }

    fun holds(
        memory: CellMemory,
        length: Int,
        queuedAt: Long,
        fill: Fill,
    ) {
        this.memory = memory
        this.length = length
        this.queuedAt = queuedAt
        this.fill = fill
    }
}

/**
 * A connection's RFC 9221 send queue, of [depth] datagrams: any thread offers, and the connection takes from the head.
 * A bounded ring of cells claimed by compare-and-set, each cell published by its own sequence, so neither side locks or
 * waits; each cell's memory is made when a datagram first needs it and reused after. A full queue refuses the new
 * datagram, or under [Overflow.EvictOldest] drops the oldest to make room, counted. Freed once the connection ended
 * and no offer is inside.
 */
@Queue
internal class DatagramQueue(
    private val depth: Int,
    private val overflow: Overflow,
    private val outgoing: AgeBound,
    private val factory: BufferFactory,
    private val clock: LoopClock,
    private val counts: ConnCounts,
) {
    private val cells = List(depth, ::QueuedDatagram)
    private val head = AtomicLong(0L)
    private val tail = AtomicLong(0L)

    /** Offers inside, and the closed mark: an offer enters unless the queue is closed, and the last one out of a closed queue frees it. */
    private val users = AtomicInt(0)

    /** Whether the connection was handed [feedWork] and has not run it yet: one hand-off serves a burst of offers. */
    private val feed = AtomicInt(IDLE)

    /** The work that feeds this queue's datagrams to quiche; in at most one inbox at a time, by [wantsFeed]. */
    val feedWork = FeedWork(this)

    /** Datagrams queued at or below which senders waiting for room are woken. */
    val lowWater: Int = depth / LOW_WATER_DIVISOR

    /** Any thread: datagrams queued now; stale by the time it returns. */
    fun size(): Int = (tail.load() - head.load()).toInt().coerceIn(0, depth)

    /**
     * Any thread: queues the [prefixLength] bytes at [prefix] and the [length] bytes at [payload] as one datagram, the
     * prefix first, copied once into a cell.
     */
    fun offer(
        prefix: Long,
        prefixLength: Int,
        payload: Long,
        length: Int,
    ): Offered {
        if (enter() == Entered.Closed) return Offered.Closed
        try {
            while (true) {
                val at = tail.load()
                val cell = cells[(at % depth).toInt()]
                val behind = cell.sequence() - at
                if (behind == 0L) {
                    if (tail.compareAndSet(at, at + 1)) return fill(cell, at, prefix, prefixLength, payload, length)
                } else if (behind < 0L) {
                    when (overflow) {
                        Overflow.RefuseNew -> return Offered.Full
                        Overflow.EvictOldest -> evictOldest()
                    }
                }
            }
        } finally {
            leave()
        }
    }

    /** Any thread, after an offer: [Feed.Hand] when the connection must be handed [feedWork], once until it runs. */
    fun wantsFeed(): Feed = if (feed.compareAndSet(IDLE, QUEUED)) Feed.Hand else Feed.Handed

    /** Inside: [feedWork] runs, so the next offer hands it again. */
    fun fed() = feed.store(IDLE)

    /** The connection, or an evicting offer: claims the oldest datagram, which stays the taker's until [release]. */
    fun take(): Head {
        while (true) {
            val at = head.load()
            val cell = cells[(at % depth).toInt()]
            val ahead = cell.sequence() - (at + 1)
            if (ahead == 0L) {
                if (head.compareAndSet(at, at + 1)) return cell.also { it.claim(at) }
            } else if (ahead < 0L) {
                return Head.Empty
            }
        }
    }

    /** The taker is done with [cell]: it is free for the datagram a whole ring later. */
    fun release(cell: QueuedDatagram) = cell.publish(cell.claimed + depth)

    /** Inside, at the end: no offer enters any more, and the cells' memory goes once the last offer inside left. */
    fun close() {
        while (true) {
            val seen = users.load()
            if (seen and CLOSED != 0) return
            if (users.compareAndSet(seen, seen or CLOSED)) {
                if (seen == 0) freeAll()
                return
            }
        }
    }

    private fun fill(
        cell: QueuedDatagram,
        at: Long,
        prefix: Long,
        prefixLength: Int,
        payload: Long,
        length: Int,
    ): Offered {
        val whole = prefixLength + length
        val memory = memoryFor(cell.memory, whole)
        val queuedAt =
            when (outgoing) {
                is AgeBound.Unbounded -> 0L
                is AgeBound.Within -> clock.now().value
            }
        val offered =
            when (memory) {
                is CellMemory.Unmade -> {
                    cell.holds(memory, 0, queuedAt, Fill.Nothing)
                    Offered.Unaddressable
                }
                is CellMemory.Made -> {
                    if (prefixLength > 0) NativeBytes.copy(prefix, memory.bytes.address, prefixLength)
                    if (length > 0) NativeBytes.copy(payload, memory.bytes.address + prefixLength, length)
                    cell.holds(memory, whole, queuedAt, Fill.Datagram)
                    Offered.Queued
                }
            }
        cell.publish(at + 1)
        return offered
    }

    /** [held], or memory of at least [needed] bytes in its place. */
    private fun memoryFor(
        held: CellMemory,
        needed: Int,
    ): CellMemory {
        when (held) {
            is CellMemory.Made -> if (held.bytes.capacity >= needed) return held else held.bytes.free()
            is CellMemory.Unmade -> Unit
        }
        val buffer = factory.allocate(maxOf(needed, DATAGRAM_ROOM))
        return when (val made = NativeBytes.of(buffer)) {
            is Addressed.Native -> CellMemory.Made(made.bytes)
            is Addressed.NotAddressable -> {
                buffer.freeNativeMemory()
                CellMemory.Unmade
            }
        }
    }

    private fun evictOldest() =
        when (val oldest = take()) {
            is Head.Empty -> Unit
            is QueuedDatagram -> {
                release(oldest)
                counts.datagramEvicted()
            }
        }

    private fun enter(): Entered {
        while (true) {
            val seen = users.load()
            if (seen and CLOSED != 0) return Entered.Closed
            if (users.compareAndSet(seen, seen + 1)) return Entered.In
        }
    }

    private fun leave() {
        if (users.decrementAndFetch() == CLOSED) freeAll()
    }

    private fun freeAll() =
        cells.forEach {
            when (val memory = it.memory) {
                is CellMemory.Made -> memory.bytes.free()
                is CellMemory.Unmade -> Unit
            }
        }

    private enum class Entered { In, Closed }

    private companion object {
        const val CLOSED = 1 shl 30
        const val IDLE = 0
        const val QUEUED = 1
        const val LOW_WATER_DIVISOR = 2
    }
}
