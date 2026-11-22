@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Queue
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What [DatagramRing.take] found at the head: a [DatagramSlot], which is its own answer, or [Empty]. */
internal sealed interface Head {
    data object Empty : Head
}

/** Whether [DatagramRing.put] placed the slot, or found the tail's cell still in the hands of a taker. */
internal enum class Put { Placed, Blocked }

/**
 * The order of a connection's queued datagrams: a ring of [capacity] cells, each holding one [DatagramSlot] between a
 * put and a take. A put claims the tail and a take the head by compare-and-set, and each cell is published by its own
 * sequence: the position it is free for, one past it once filled, and that plus the capacity once taken. A cell is
 * held only for the two stores between a claim and its publication, and a put that finds the tail's cell not yet
 * published by its taker tries [PATIENCE] times, then answers [Put.Blocked]; nobody waits on another thread beyond
 * that. How many datagrams may wait is bounded by the queue in front, not by the ring.
 */
@Queue
internal class DatagramRing(
    private val capacity: Int,
) {
    private val sequences = AtomicLongArray(capacity)
    private val cells = Array<Head>(capacity) { Head.Empty }
    private val head = AtomicLong(0L)
    private val tail = AtomicLong(0L)

    init {
        for (index in 0 until capacity) sequences.storeAt(index, index.toLong())
    }

    /** Any thread: [slot] joins the tail. */
    fun put(slot: DatagramSlot): Put {
        var tries = 0
        while (true) {
            val at = tail.load()
            val cell = (at % capacity).toInt()
            val behind = sequences.loadAt(cell) - at
            if (behind == 0L) {
                if (tail.compareAndSet(at, at + 1)) {
                    cells[cell] = slot
                    sequences.storeAt(cell, at + 1)
                    return Put.Placed
                }
            } else if (behind < 0L && ++tries >= PATIENCE) {
                return Put.Blocked
            }
        }
    }

    /** Any thread: the oldest slot, which is the taker's; [Head.Empty] when none is published at the head. */
    fun take(): Head {
        while (true) {
            val at = head.load()
            val cell = (at % capacity).toInt()
            val ahead = sequences.loadAt(cell) - (at + 1)
            if (ahead == 0L) {
                if (head.compareAndSet(at, at + 1)) {
                    val slot = cells[cell]
                    cells[cell] = Head.Empty
                    sequences.storeAt(cell, at + capacity)
                    return slot
                }
            } else if (ahead < 0L) {
                return Head.Empty
            }
        }
    }

    private companion object {
        const val PATIENCE = 64
    }
}
