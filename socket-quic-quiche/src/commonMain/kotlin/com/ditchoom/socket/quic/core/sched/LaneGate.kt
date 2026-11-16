@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Who closes a shut socket once its last lane is back. */
internal enum class Closer {
    /** The loop: the last lane back wakes it, and it closes in its next pass. */
    Loop,

    /** The thread that gives the last lane back closes the socket itself: the loop has stopped. */
    LastLane,
}

/** What [LaneGate.shut] answers. */
internal sealed interface Shutdown {
    /** No lane is out and none can be taken again: the thread that asked closes the socket now. */
    data object Quiet : Shutdown

    /** Lanes are still out; the last one back finishes the close the way the [Closer] says. */
    data object LanesOut : Shutdown
}

/** What [LaneGate.reserve] answers. */
internal enum class Lend { Lent, Shut }

/** What a lane's return led to. */
internal enum class LaneBack {
    Returned,

    /** It was the last lane out of a socket the loop shut: the loop closes it. */
    LoopCloses,

    /** It was the last lane out of a socket whose loop stopped: this thread closes it. */
    ThisThreadCloses,
}

/**
 * Lanes out of a socket, and its shutdown, in one word. A thread sends on the socket only from a lane it holds or as
 * the loop; a shut socket lends no lane, and it is closed only once every lane is back. So a socket closed while a
 * thread can still send on it has no encoding.
 *
 * ```
 * bits 63..62  00 open | 01 shut, the loop closes | 10 shut, the last lane back closes | 11 closed
 * bits 31..0   lanes out
 * ```
 *
 * The state only moves forward and every transition is one compare-and-set, so no word is ever seen twice and a
 * stale read cannot pass for a current one.
 */
@Published
internal class LaneGate {
    private val word = AtomicLong(OPEN)

    /** Any thread: reserves a lane, unless the socket is shut. */
    fun reserve(): Lend {
        while (true) {
            val seen = word.load()
            if ((seen and STATE_MASK) != OPEN) return Lend.Shut
            if (word.compareAndSet(seen, seen + 1)) return Lend.Lent
        }
    }

    /** Any thread: a lane reserved by [reserve] is back. */
    fun back(): LaneBack {
        while (true) {
            val seen = word.load()
            val left = (seen and COUNT_MASK) - 1
            val state = seen and STATE_MASK
            val next = if (left == 0L && state == SHUT_LAST) CLOSED else state or left
            if (!word.compareAndSet(seen, next)) continue
            if (left != 0L) return LaneBack.Returned
            return when (state) {
                SHUT_LOOP -> LaneBack.LoopCloses
                SHUT_LAST -> LaneBack.ThisThreadCloses
                else -> LaneBack.Returned
            }
        }
    }

    /**
     * Loop thread: no more lanes are lent. [Shutdown.Quiet] when none is out, and then the socket counts as closed;
     * otherwise the last lane back finishes the close as [closer] says. Asked again, it answers again.
     */
    fun shut(closer: Closer): Shutdown {
        val shutState =
            when (closer) {
                Closer.Loop -> SHUT_LOOP
                Closer.LastLane -> SHUT_LAST
            }
        while (true) {
            val seen = word.load()
            val state = seen and STATE_MASK
            if (state == CLOSED) return Shutdown.Quiet
            val out = seen and COUNT_MASK
            // A stopped loop's close is never handed back to the loop.
            val kept = if (state == SHUT_LAST) SHUT_LAST else shutState
            val next = if (out == 0L) CLOSED else kept or out
            if (word.compareAndSet(seen, next)) return if (out == 0L) Shutdown.Quiet else Shutdown.LanesOut
        }
    }

    /** Diagnostics and tests: how many lanes are out. */
    fun lanesOut(): Int = (word.load() and COUNT_MASK).toInt()
}

private const val STATE_MASK = 3L shl 62
private const val OPEN = 0L
private const val SHUT_LOOP = 1L shl 62
private const val SHUT_LAST = 2L shl 62
private const val CLOSED = 3L shl 62
private const val COUNT_MASK = (1L shl 32) - 1
