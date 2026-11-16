@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.pool

import com.ditchoom.socket.quic.core.observe.Published
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch

/** What [MemoryHold.acquire] answers. */
internal enum class Hold { Held, AlreadyFreed }

/**
 * Memory shared by a loop and every connection on it, freed when the last holder lets go: the loop holds it from the
 * start, and each connection from its build until nobody is inside it. Once freed it is never held again.
 */
@Published
internal class MemoryHold(
    private val freeAll: Runnable,
) {
    private val holders = AtomicInt(1)

    fun acquire(): Hold {
        while (true) {
            val seen = holders.load()
            if (seen == 0) return Hold.AlreadyFreed
            if (holders.compareAndSet(seen, seen + 1)) return Hold.Held
        }
    }

    fun release() {
        if (holders.decrementAndFetch() == 0) freeAll.run()
    }
}
