@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Pool
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/** A slot's memory: none yet, or bytes made for it, kept until the pool trims or frees them. */
internal sealed interface SlotMemory {
    data object Unmade : SlotMemory

    class Made(
        val bytes: NativeBytes,
    ) : SlotMemory
}

/** What [DatagramSlots.take] found: a [DatagramSlot], which is its own answer, or [None] when every slot is taken. */
internal sealed interface SlotTake {
    data object None : SlotTake
}

/** Where a slot of the pool stands: not made yet, or the [DatagramSlot] made for its place. */
internal sealed interface SlotPlace {
    data object Unborn : SlotPlace
}

/**
 * One datagram's memory, borrowed from a [DatagramSlots] by whoever fills it and handed on with it: from a sender into
 * the queue, and from the queue to the connection, which gives it back. Only its holder touches it.
 */
@Pool
internal class DatagramSlot(
    val index: Int,
) : SlotTake,
    SlotPlace,
    Head {
    var memory: SlotMemory = SlotMemory.Unmade
        private set
    var length = 0
        private set
    var queuedAt = 0L
        private set

    fun holds(memory: SlotMemory) {
        this.memory = memory
    }

    fun carries(
        length: Int,
        queuedAt: Long,
    ) {
        this.length = length
        this.queuedAt = queuedAt
    }
}

/**
 * A connection's datagram memory: at most [capacity] slots, each made the first time one is needed. Slots come back
 * when their datagram leaves; those that hold memory are kept for the next send, and [trim] frees all but as many as
 * recent use needs, so memory follows how many datagrams wait. Two free stacks of slot indices, one of slots with memory
 * and one without, each a head word of the top index and a stamp every change increments, so no take or give locks.
 */
@Pool
internal class DatagramSlots(
    private val capacity: Int,
) {
    private val places = Array<SlotPlace>(capacity) { SlotPlace.Unborn }
    private val below = IntArray(capacity)
    private val withMemory = AtomicLong(EMPTY_STACK)
    private val withoutMemory = AtomicLong(EMPTY_STACK)
    private val born = AtomicInt(0)
    private val kept = AtomicInt(0)
    private val birth: (Int) -> DatagramSlot = ::DatagramSlot

    /** Any thread: a free slot, one with memory first; [SlotTake.None] when all [capacity] are taken. */
    fun take(): SlotTake {
        when (val made = pop(withMemory)) {
            is DatagramSlot -> return made.also { kept.decrementAndFetch() }
            is SlotTake.None -> Unit
        }
        when (val bare = pop(withoutMemory)) {
            is DatagramSlot -> return bare
            is SlotTake.None -> Unit
        }
        while (true) {
            val next = born.load()
            if (next == capacity) return SlotTake.None
            if (born.compareAndSet(next, next + 1)) return birth(next).also { places[next] = it }
        }
    }

    /** Any thread: gives back a slot taken from this pool. */
    fun give(slot: DatagramSlot) =
        when (slot.memory) {
            is SlotMemory.Made -> {
                push(withMemory, slot.index)
                kept.incrementAndFetch()
            }
            is SlotMemory.Unmade -> push(withoutMemory, slot.index)
        }

    /** Free slots that hold memory now; stale by the time it returns. */
    fun keptWithMemory(): Int = kept.load()

    /** Slots made so far that hold memory, taken or free; stale by the time it returns. */
    fun holdingMemory(): Int =
        places.count { place ->
            when (place) {
                is DatagramSlot -> place.memory is SlotMemory.Made
                is SlotPlace.Unborn -> false
            }
        }

    /** Slots made so far, taken or free. */
    fun made(): Int = born.load()

    /** Frees the memory of free slots until at most [keep] of them hold any. */
    fun trim(keep: Int) {
        while (kept.load() > keep) {
            val slot =
                when (val top = pop(withMemory)) {
                    is DatagramSlot -> top
                    is SlotTake.None -> return
                }
            kept.decrementAndFetch()
            when (val memory = slot.memory) {
                is SlotMemory.Made -> memory.bytes.free()
                is SlotMemory.Unmade -> Unit
            }
            slot.holds(SlotMemory.Unmade)
            push(withoutMemory, slot.index)
        }
    }

    /** Once nobody holds a slot: every slot's memory goes. */
    fun freeAll() {
        places.forEach { place ->
            when (place) {
                is DatagramSlot ->
                    when (val memory = place.memory) {
                        is SlotMemory.Made -> {
                            memory.bytes.free()
                            place.holds(SlotMemory.Unmade)
                        }
                        is SlotMemory.Unmade -> Unit
                    }
                is SlotPlace.Unborn -> Unit
            }
        }
    }

    /** The slot on top of [stack], taken off it; a slot reaches a stack only once it was born into its place. */
    private fun pop(stack: AtomicLong): SlotTake {
        while (true) {
            val seen = stack.load()
            val top = topOf(seen)
            if (top == 0) return SlotTake.None
            if (stack.compareAndSet(seen, pack(stampOf(seen) + 1, below[top - 1]))) {
                return when (val place = places[top - 1]) {
                    is DatagramSlot -> place
                    is SlotPlace.Unborn -> SlotTake.None
                }
            }
        }
    }

    private fun push(
        stack: AtomicLong,
        index: Int,
    ) {
        while (true) {
            val seen = stack.load()
            below[index] = topOf(seen)
            if (stack.compareAndSet(seen, pack(stampOf(seen) + 1, index + 1))) return
        }
    }

    private companion object {
        const val EMPTY_STACK = 0L
        const val TOP_MASK = 0xFFFF_FFFFL

        fun pack(
            stamp: Long,
            top: Int,
        ): Long = (stamp shl 32) or (top.toLong() and TOP_MASK)

        fun topOf(word: Long): Int = (word and TOP_MASK).toInt()

        fun stampOf(word: Long): Long = word ushr 32
    }
}
