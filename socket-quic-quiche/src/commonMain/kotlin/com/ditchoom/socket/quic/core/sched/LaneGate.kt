@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Who closes a shut socket once the last thread that could send on it is out. */
internal enum class Closer {
    /** The loop: the last thread out wakes it, and it closes in its next pass. */
    Loop,

    /** The last thread out closes the socket itself: the loop has stopped. */
    LastLane,
}

/** What [LaneGate.shut] and [LaneGate.cut] answer. */
internal sealed interface Shutdown {
    /** Nothing can send on the socket any more: the thread that asked closes it now. */
    data object Quiet : Shutdown

    /** Lanes are still out; the last one back finishes the close the way the [Closer] says. */
    data object LanesOut : Shutdown

    /** The lanes were cut and a thread is inside a send; the last one out finishes the close the way the [Closer] says. */
    data object Sending : Shutdown
}

/** What [LaneGate.reserve] answers. */
internal enum class Lend { Lent, Shut }

/** What [LaneGate.enterSend] answers. */
internal enum class SendEntry {
    /** The thread may call the socket until it calls [LaneGate.leaveSend]. */
    Entered,

    /** The socket was cut from its lanes: the thread must not call it. */
    Cut,
}

/** What a lane's return, or a thread's leaving a send, led to. */
internal enum class LaneBack {
    Returned,

    /** It was the last thread out of a socket the loop shut: the loop closes it. */
    LoopCloses,

    /** It was the last thread out of a socket whose loop stopped: this thread closes it. */
    ThisThreadCloses,
}

/**
 * Lanes out of a socket, the threads inside a send on it, and its shutdown, in one word. A thread other than the loop
 * calls the socket only between [enterSend] and [leaveSend], from a lane it holds. A shut socket lends no lane and is
 * closed once every lane is back; a cut one, for lanes that stalled past a bound, admits no send and is closed once no
 * thread is inside one, with lanes still out. Whoever moves the word to closed closes the socket, and a closed word
 * admits no send, so a call on a closed or reused descriptor has no encoding.
 *
 * ```
 * bits 63..61  0 open | 1 shut, the loop closes | 2 shut, the last lane back closes
 *              3 cut, the loop closes | 4 cut, the last sender out closes | 5 closed
 * bits 60..32  threads inside a send
 * bits 31..0   lanes out
 * ```
 *
 * The state only moves forward and every transition is one compare-and-set, so no word is ever seen twice and a
 * stale read cannot pass for a current one.
 */
@Published
internal class LaneGate {
    private val word = AtomicLong(OPEN_WORD)

    /** Any thread: reserves a lane, unless the socket is shut. */
    fun reserve(): Lend {
        while (true) {
            val seen = word.load()
            if (stateOf(seen) != OPEN) return Lend.Shut
            if (word.compareAndSet(seen, seen + LANE)) return Lend.Lent
        }
    }

    /** Any thread: a lane reserved by [reserve] is back. */
    fun back(): LaneBack {
        while (true) {
            val seen = word.load()
            val left = (seen and LANES_MASK) - 1
            val state = stateOf(seen)
            // A shut socket's senders all hold lanes, so with no lane out none is inside a send.
            val next = if (left == 0L && state == SHUT_LAST) CLOSED_WORD else seen - LANE
            if (!word.compareAndSet(seen, next)) continue
            if (left != 0L) return LaneBack.Returned
            return when (state) {
                SHUT_LOOP -> LaneBack.LoopCloses
                SHUT_LAST -> LaneBack.ThisThreadCloses
                else -> LaneBack.Returned
            }
        }
    }

    /** Any thread holding a lane: about to call the socket, unless it was cut. */
    fun enterSend(): SendEntry {
        while (true) {
            val seen = word.load()
            if (stateOf(seen) >= CUT_LOOP) return SendEntry.Cut
            if (word.compareAndSet(seen, seen + SENDER)) return SendEntry.Entered
        }
    }

    /** Any thread: a send entered by [enterSend] returned from the socket. */
    fun leaveSend(): LaneBack {
        while (true) {
            val seen = word.load()
            val inside = ((seen and SENDERS_MASK) ushr SENDERS_SHIFT) - 1
            val state = stateOf(seen)
            val next = if (inside == 0L && state == CUT_LAST) closed(seen - SENDER) else seen - SENDER
            if (!word.compareAndSet(seen, next)) continue
            if (inside != 0L) return LaneBack.Returned
            return when (state) {
                CUT_LOOP -> LaneBack.LoopCloses
                CUT_LAST -> LaneBack.ThisThreadCloses
                else -> LaneBack.Returned
            }
        }
    }

    /**
     * Loop thread: no more lanes are lent. [Shutdown.Quiet] when nothing can send any more, and then the socket counts
     * as closed; otherwise the last thread out finishes the close as [closer] says. Asked again, it answers again.
     */
    fun shut(closer: Closer): Shutdown {
        while (true) {
            val seen = word.load()
            val state = stateOf(seen)
            if (state == CLOSED) return Shutdown.Quiet
            if (state >= CUT_LOOP) return cut(closer)
            val out = seen and LANES_MASK
            // A stopped loop's close is never handed back to the loop.
            val kept = if (state == SHUT_LAST || closer == Closer.LastLane) SHUT_LAST else SHUT_LOOP
            val next = if (out == 0L) CLOSED_WORD else withState(seen, kept)
            if (word.compareAndSet(seen, next)) return if (out == 0L) Shutdown.Quiet else Shutdown.LanesOut
        }
    }

    /**
     * Loop thread, once lanes stalled past their bound: no lane may call the socket any more, whether or not it is
     * back. [Shutdown.Quiet] when no thread is inside a send, and then the socket counts as closed; otherwise the last
     * one out finishes the close as [closer] says. Asked again, it answers again.
     */
    fun cut(closer: Closer): Shutdown {
        while (true) {
            val seen = word.load()
            val state = stateOf(seen)
            if (state == CLOSED) return Shutdown.Quiet
            val inside = seen and SENDERS_MASK
            // A stopped loop's close is never handed back to the loop.
            val kept = if (state == SHUT_LAST || state == CUT_LAST || closer == Closer.LastLane) CUT_LAST else CUT_LOOP
            val next = if (inside == 0L) closed(seen) else withState(seen, kept)
            if (word.compareAndSet(seen, next)) return if (inside == 0L) Shutdown.Quiet else Shutdown.Sending
        }
    }

    /** Diagnostics and tests: how many lanes are out. */
    fun lanesOut(): Int = (word.load() and LANES_MASK).toInt()

    /** Diagnostics and tests: how many threads are inside a send. */
    fun sending(): Int = ((word.load() and SENDERS_MASK) ushr SENDERS_SHIFT).toInt()
}

private const val STATE_SHIFT = 61
private const val STATE_MASK = 7L shl STATE_SHIFT
private const val OPEN = 0L
private const val SHUT_LOOP = 1L
private const val SHUT_LAST = 2L
private const val CUT_LOOP = 3L
private const val CUT_LAST = 4L
private const val CLOSED = 5L
private const val OPEN_WORD = 0L
private const val CLOSED_WORD = CLOSED shl STATE_SHIFT
private const val SENDERS_SHIFT = 32
private const val SENDERS_MASK = ((1L shl 29) - 1) shl SENDERS_SHIFT
private const val SENDER = 1L shl SENDERS_SHIFT
private const val LANES_MASK = (1L shl 32) - 1
private const val LANE = 1L

private fun stateOf(word: Long): Long = (word and STATE_MASK) ushr STATE_SHIFT

private fun withState(
    word: Long,
    state: Long,
): Long = (word and STATE_MASK.inv()) or (state shl STATE_SHIFT)

/** The word closed, keeping the lanes still out so each can come back. */
private fun closed(word: Long): Long = (word and LANES_MASK) or CLOSED_WORD
