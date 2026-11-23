@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Queue
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** What fills a step slot that holds no step. Never run. */
internal object NoStep : Runnable {
    override fun run() = Unit
}

/** A ring's slots: each index holds a step and the context it was dispatched with. */
private class StepCells(
    val blocks: Array<Runnable>,
    val contexts: Array<CoroutineContext>,
) {
    val mask = blocks.size - 1

    constructor(capacity: Int) : this(Array(capacity) { NoStep }, Array(capacity) { EmptyCoroutineContext })
}

/**
 * A growable ring of coroutine steps with one producer and one consumer, which may be different threads. A step is
 * a slot write and an index store, so the producer allocates only when the ring grows; the consumer clears each slot it
 * took, so a run step is not kept.
 */
@Queue
internal class StepRing {
    private val cells = AtomicReference(StepCells(INITIAL))
    private val head = AtomicLong(0L)
    private val tail = AtomicLong(0L)

    /** The producer only. */
    fun push(
        block: Runnable,
        context: CoroutineContext,
    ) {
        val t = tail.load()
        var c = cells.load()
        val h = head.load()
        if (t - h == c.blocks.size.toLong()) {
            c = grown(c, h, t)
            cells.store(c)
            // The consumer may have taken steps from the old slots while they were copied: their copies are cleared.
            clearTaken(c, h, head.load())
        }
        val at = (t and c.mask.toLong()).toInt()
        c.blocks[at] = block
        c.contexts[at] = context
        tail.store(t + 1)
    }

    /** The consumer only: runs the oldest step through [run] and answers whether there was one. */
    fun takeInto(run: (Runnable, CoroutineContext) -> Unit): Boolean {
        val h = head.load()
        if (h == tail.load()) return false
        val c = cells.load()
        val at = (h and c.mask.toLong()).toInt()
        val block = c.blocks[at]
        val context = c.contexts[at]
        c.blocks[at] = NoStep
        c.contexts[at] = EmptyCoroutineContext
        head.store(h + 1)
        run(block, context)
        return true
    }

    /** Any thread: the steps pushed and not yet taken, at the moment of reading. */
    fun size(): Int = (tail.load() - head.load()).toInt()

    /** Twice the slots, holding every step from [h], the consumer's head as the producer last saw it, to [t]. */
    private fun grown(
        c: StepCells,
        h: Long,
        t: Long,
    ): StepCells {
        val next = StepCells(c.blocks.size * 2)
        var i = h
        while (i < t) {
            val from = (i and c.mask.toLong()).toInt()
            val to = (i and next.mask.toLong()).toInt()
            next.blocks[to] = c.blocks[from]
            next.contexts[to] = c.contexts[from]
            i++
        }
        return next
    }

    /** Clears [c]'s slots of the indices from [from] to [taken], which the consumer is past and never reads again. */
    private fun clearTaken(
        c: StepCells,
        from: Long,
        taken: Long,
    ) {
        var i = from
        while (i < taken) {
            val at = (i and c.mask.toLong()).toInt()
            c.blocks[at] = NoStep
            c.contexts[at] = EmptyCoroutineContext
            i++
        }
    }

    private companion object {
        const val INITIAL = 8
    }
}
