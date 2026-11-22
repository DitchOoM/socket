@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Queue
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What a take from a ready queue answers: a lane it now holds, or nothing waiting. */
internal sealed interface ReadyTake {
    data object None : ReadyTake
}

/**
 * A growable ring of ready lanes with one owner, which pushes, and any number of takers, each of which takes the
 * oldest by advancing the head with a compare-and-set. Indices only grow, so a taker that read a slot after the owner
 * reused it fails its compare-and-set rather than taking the wrong lane. The owner clears the slots taken since its
 * last push before it writes, so a lane taken is not kept; it allocates only when the ring grows.
 */
@Queue
internal class LaneRing(
    /** The slots it starts with, a power of two. */
    initial: Int = INITIAL,
) {
    private val cells = AtomicReference(Array<ReadyTake>(initial) { ReadyTake.None })
    private val head = AtomicLong(0L)
    private val tail = AtomicLong(0L)

    /** Owner only: the index below which every slot has been cleared. */
    private var cleared = 0L

    /** The owner only. */
    fun push(lane: HandlerLane) {
        val t = tail.load()
        val h = head.load()
        var c = cells.load()
        if (t - h == c.size.toLong()) {
            c = grown(c, h, t)
            cells.store(c)
            cleared = h
        }
        val mask = (c.size - 1).toLong()
        while (cleared < h) {
            c[(cleared and mask).toInt()] = ReadyTake.None
            cleared++
        }
        c[(t and mask).toInt()] = lane
        tail.store(t + 1)
    }

    /** Any thread: the oldest lane, which the caller now holds, or [ReadyTake.None]. */
    fun take(): ReadyTake {
        while (true) {
            val h = head.load()
            if (h >= tail.load()) return ReadyTake.None
            val c = cells.load()
            val seen = c[(h and (c.size - 1).toLong()).toInt()]
            if (head.compareAndSet(h, h + 1)) return seen
        }
    }

    /** Any thread: whether a lane waited at the moment of reading. */
    fun hasWaiting(): Boolean = head.load() < tail.load()

    private fun grown(
        c: Array<ReadyTake>,
        h: Long,
        t: Long,
    ): Array<ReadyTake> {
        val next = Array<ReadyTake>(c.size * 2) { ReadyTake.None }
        var i = h
        while (i < t) {
            next[(i and (next.size - 1).toLong()).toInt()] = c[(i and (c.size - 1).toLong()).toInt()]
            i++
        }
        return next
    }

    private companion object {
        const val INITIAL = 16
    }
}
