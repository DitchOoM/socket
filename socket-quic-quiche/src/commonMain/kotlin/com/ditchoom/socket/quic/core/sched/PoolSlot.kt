@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/**
 * What running code the pool does not own answered: it returned, or it threw [Caught.Threw.thrown]. A pool is given
 * the function that runs such code at a containment site of the edge's, so the pool itself never catches.
 */
internal sealed interface Caught {
    data object Clean : Caught

    class Threw(
        val thrown: Thrown,
    ) : Caught
}

/** Whether a loop set's pool is made yet. */
private sealed interface Slot {
    data object Unmade : Slot

    class Made(
        val pool: HandlerPool,
    ) : Slot

    data object Closed : Slot
}

/**
 * A client loop set's handler pool, made by the first connection that runs handlers, which hands it the containment
 * the pool runs steps under; stopped with the set.
 */
@Published
internal class PoolSlot(
    private val threads: HandlerThreads,
    private val name: String,
    private val stallBound: Duration,
) {
    private val slot = AtomicReference<Slot>(Slot.Unmade)

    /** Any thread: the set's pool, made now under [contain] if no connection made it before; a stopped one once closed. */
    fun pool(contain: (Runnable) -> Caught): HandlerPool {
        while (true) {
            when (val now = slot.load()) {
                is Slot.Made -> return now.pool
                is Slot.Closed -> return HandlerPool(threads, name, contain, stallBound).also { it.stop() }
                is Slot.Unmade -> {
                    val made = HandlerPool(threads, name, contain, stallBound)
                    if (slot.compareAndSet(now, Slot.Made(made))) return made
                }
            }
        }
    }

    /** Any thread, as the set closes: its pool stops, and none is made after. */
    fun stop() {
        when (val was = slot.exchange(Slot.Closed)) {
            is Slot.Made -> was.pool.stop()
            is Slot.Unmade, is Slot.Closed -> Unit
        }
    }
}
