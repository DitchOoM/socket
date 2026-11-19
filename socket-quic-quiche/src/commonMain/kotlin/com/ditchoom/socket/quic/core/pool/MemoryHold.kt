@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.pool

import com.ditchoom.socket.quic.core.observe.Published
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch

/** What [MemoryHold.acquire] answers. */
internal sealed interface Hold {
    /** The memory is held until [claim] is released. */
    class Held(
        val claim: MemoryHold.Claim,
    ) : Hold

    data object AlreadyFreed : Hold
}

/** What [MemoryHold.Claim.release] did. */
internal enum class ClaimRelease { Released, AlreadyReleased }

/**
 * Memory shared by a loop and every connection on it, freed when the last holder lets go: the loop holds it from the
 * start through [founding], and each connection through the claim [acquire] gave it. Each claim lets go once, so no
 * holder can release another's share; once freed the memory is never held again.
 */
@Published
internal class MemoryHold(
    private val freeAll: Runnable,
) {
    private val holders = AtomicInt(1)

    /** The claim the hold is made with: its maker's. */
    val founding = Claim()

    /** Any thread: a claim on the memory, unless it was freed already. */
    fun acquire(): Hold {
        while (true) {
            val seen = holders.load()
            if (seen == 0) return Hold.AlreadyFreed
            if (holders.compareAndSet(seen, seen + 1)) return Hold.Held(Claim())
        }
    }

    private fun letGo() {
        if (holders.decrementAndFetch() == 0) freeAll.run()
    }

    /** One holder's share: released once, by one compare-and-set; a second release does nothing. Made only by its hold. */
    @Published
    inner class Claim internal constructor() {
        private val state = AtomicInt(HELD)

        fun release(): ClaimRelease {
            if (!state.compareAndSet(HELD, RELEASED)) return ClaimRelease.AlreadyReleased
            letGo()
            return ClaimRelease.Released
        }
    }

    private companion object {
        const val HELD = 0
        const val RELEASED = 1
    }
}
