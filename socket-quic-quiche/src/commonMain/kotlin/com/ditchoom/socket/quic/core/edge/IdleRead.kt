@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What a connection's idle read slot holds: nothing, a read's unused buffer with its answer, or nothing ever again. */
internal sealed interface Idle {
    data object Empty : Idle

    /** The connection ended: a buffer kept now is freed instead. */
    data object Closed : Idle
}

/**
 * The one read buffer a connection keeps, already made into its answer, for its next read: a read that ended without
 * handing its buffer to its caller (the stream finished or was reset) leaves it here. A caller makes its read's buffer
 * on its own thread before handing the read over ([take]), so whoever runs the read, the loop included, allocates
 * nothing for it; a kept buffer makes that free too, where handing it back to a pooled factory would allocate a node.
 */
@Published
internal class IdleRead {
    private val slot = AtomicReference<Idle>(Idle.Empty)

    /** Any thread: the kept answer when its buffer holds [capacity] bytes, else a new one from [factory]; limited to [capacity]. */
    fun take(
        capacity: Int,
        factory: BufferFactory,
    ): ReadAnswer.Data {
        val into =
            when (val kept = slot.load()) {
                is ReadAnswer.Data -> claimed(kept, capacity, factory)
                is Idle.Empty, is Idle.Closed -> fresh(capacity, factory)
            }
        // A pooled factory may return a larger buffer than asked for; the read asks for the chunk, not the buffer.
        into.buffer.position(0)
        into.buffer.setLimit(capacity)
        return into
    }

    /** Inside the connection: keeps [unused] for the next read; when one is already kept, or the connection ended, frees it. */
    fun keep(unused: ReadAnswer.Data) {
        if (!slot.compareAndSet(Idle.Empty, unused)) unused.buffer.freeNativeMemory()
    }

    /** Inside, once the connection ended: frees what is kept, and every later [keep] frees its buffer. */
    fun close() {
        when (val kept = slot.exchange(Idle.Closed)) {
            is ReadAnswer.Data -> kept.buffer.freeNativeMemory()
            is Idle.Empty, is Idle.Closed -> Unit
        }
    }

    /** [kept] when this thread takes it from the slot and it holds [capacity] bytes, else a new answer. */
    private fun claimed(
        kept: ReadAnswer.Data,
        capacity: Int,
        factory: BufferFactory,
    ): ReadAnswer.Data {
        if (!slot.compareAndSet(kept, Idle.Empty)) return fresh(capacity, factory)
        if (kept.buffer.capacity >= capacity) return kept
        kept.buffer.freeNativeMemory()
        return fresh(capacity, factory)
    }

    private fun fresh(
        capacity: Int,
        factory: BufferFactory,
    ) = ReadAnswer.Data(factory.allocate(capacity))
}
