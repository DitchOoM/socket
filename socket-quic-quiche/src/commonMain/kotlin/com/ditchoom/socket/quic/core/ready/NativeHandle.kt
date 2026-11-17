@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.ready

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch

/** What [NativeHandle.close] did. */
internal enum class HandleClose {
    /** This close released the handle. */
    Released,

    /** Calls were inside: the last one out releases it. */
    LastCallReleases,

    /** It was closed already: nothing was done. */
    AlreadyClosed,
}

/**
 * A native handle and the calls inside it, in one word: whether it is open, and how many calls are in flight. A call
 * runs only on an open handle; [close] moves it to closed once, by one compare-and-set, and the handle is released
 * exactly once, by that close when nothing is inside or else by the last call out. A second close, or a call after the
 * close, never reaches native code.
 *
 * ```
 * bit 30       0 open | 1 closed
 * bits 29..0   calls in flight
 * ```
 */
@Published
internal class NativeHandle(
    private val handle: Long,
    private val release: (handle: Long) -> Unit,
) {
    private val word = AtomicInt(0)

    /** Any thread: runs [call] with the handle while counted inside, or answers [closed] without calling it. Allocates nothing. */
    inline fun <R> use(
        call: (handle: Long) -> R,
        closed: () -> R,
    ): R {
        while (true) {
            val seen = load()
            if (seen and CLOSED != 0) return closed()
            if (!enter(seen)) continue
            try {
                return call(handleForCall())
            } finally {
                leave()
            }
        }
    }

    /** Any thread: closes the handle once; it is released now, or by the last call still inside. */
    fun close(): HandleClose {
        while (true) {
            val seen = word.load()
            if (seen and CLOSED != 0) return HandleClose.AlreadyClosed
            if (!word.compareAndSet(seen, seen or CLOSED)) continue
            if (seen != 0) return HandleClose.LastCallReleases
            release(handle)
            return HandleClose.Released
        }
    }

    @PublishedApi
    internal fun load(): Int = word.load()

    @PublishedApi
    internal fun enter(seen: Int): Boolean = word.compareAndSet(seen, seen + 1)

    @PublishedApi
    internal fun handleForCall(): Long = handle

    @PublishedApi
    internal fun leave() {
        if (word.decrementAndFetch() == CLOSED) release(handle)
    }

    @PublishedApi
    internal companion object {
        const val CLOSED = 1 shl 30
    }
}
