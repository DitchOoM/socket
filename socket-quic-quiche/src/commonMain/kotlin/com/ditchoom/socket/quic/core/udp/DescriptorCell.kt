@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether a cell was quiesced: a closed one never is again. */
internal enum class Quiesced { Quiet, WasClosed }

/** What replaced a descriptor: a new one, or nothing (the cell is closed). */
internal sealed interface Swapped {
    class To(
        val fd: Int,
    ) : Swapped

    data object Gone : Swapped
}

/**
 * A native socket's descriptor and the senders using it, in one word: the descriptor, the cell's phase and the count of
 * calls in flight. The loop closes a descriptor only once no call is in flight on it, so a descriptor number the
 * kernel hands out again after the close can never receive a datagram meant for this socket.
 */
@Published
internal class DescriptorCell(
    first: Int,
) {
    private val word = AtomicLong(pack(first, LIVE, 0))

    /** The descriptor, for the loop thread, which alone replaces or closes it. */
    fun descriptor(): Int = fdOf(word.load())

    /**
     * Any thread: runs [call] with the descriptor while counted in flight, or answers [replacing] or [closed] without
     * calling it. Allocates nothing.
     */
    inline fun <R> use(
        call: (fd: Int) -> R,
        replacing: () -> R,
        closed: () -> R,
    ): R {
        while (true) {
            val seen = load()
            when (phaseOf(seen)) {
                REPLACING -> return replacing()
                CLOSED -> return closed()
            }
            if (!enter(seen)) continue
            try {
                return call(fdOf(seen))
            } finally {
                leave()
            }
        }
    }

    /**
     * Loop thread: stops new calls, waits out the ones in flight, then runs [swap] on the old descriptor: its successor
     * goes live, or the cell stays closed. A closed cell runs nothing: its descriptor number may be another socket's now.
     */
    fun replace(swap: (old: Int) -> Swapped): Quiesced {
        val quiesced = quiesce(REPLACING)
        if (quiesced == Quiesced.WasClosed) return quiesced
        val old = fdOf(word.load())
        when (val swapped = swap(old)) {
            is Swapped.To -> word.store(pack(swapped.fd, LIVE, 0))
            is Swapped.Gone -> word.store(pack(old, CLOSED, 0))
        }
        return quiesced
    }

    /**
     * Any thread: stops new calls, waits out the ones in flight, then runs [close] on the descriptor. The move to
     * closed is one compare-and-set from live, so of two closes, racing or not, only the one that made it runs [close].
     */
    fun close(close: (fd: Int) -> Unit) {
        if (quiesce(CLOSED) == Quiesced.WasClosed) return
        close(fdOf(word.load()))
    }

    @PublishedApi
    internal fun load(): Long = word.load()

    @PublishedApi
    internal fun enter(seen: Long): Boolean = word.compareAndSet(seen, seen + 1)

    @PublishedApi
    internal fun leave() {
        while (true) {
            val seen = word.load()
            if (word.compareAndSet(seen, seen - 1)) return
        }
    }

    /** Moves a live cell to [phase] and waits out the calls in flight; a replace under way is waited out first. */
    private fun quiesce(phase: Long): Quiesced {
        while (true) {
            val seen = word.load()
            when (phaseOf(seen)) {
                CLOSED -> return Quiesced.WasClosed
                REPLACING -> continue
            }
            if (word.compareAndSet(seen, pack(fdOf(seen), phase, usersOf(seen)))) break
        }
        while (usersOf(word.load()) != 0L) Unit
        return Quiesced.Quiet
    }

    @PublishedApi
    internal companion object {
        const val LIVE = 0L
        const val REPLACING = 1L
        const val CLOSED = 2L
        private const val USERS_BITS = 30
        private const val USERS_MASK = (1L shl USERS_BITS) - 1

        fun pack(
            fd: Int,
            phase: Long,
            users: Long,
        ): Long = (fd.toLong() shl 32) or (phase shl USERS_BITS) or users

        fun fdOf(word: Long): Int = (word ushr 32).toInt()

        fun phaseOf(word: Long): Long = (word ushr USERS_BITS) and 3L

        fun usersOf(word: Long): Long = word and USERS_MASK
    }
}
