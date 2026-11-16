package com.ditchoom.socket.quic.core.stream

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.core.observe.Confined

/**
 * The one read buffer a connection keeps for its next read when a read ended without handing its buffer to a caller
 * (the stream finished or was reset). Returning that buffer to a pooled factory allocates a node there; keeping it
 * costs nothing, and the next read starts with a buffer already in hand. Inside the connection only.
 */
@Confined
internal class IdleBuffer {
    /** Holds at most one buffer, and its single slot is made once, so keeping and taking allocate nothing. */
    private val kept = ArrayList<PlatformBuffer>(1)

    /** Keeps [buffer] for the next read; when one is already kept, [buffer] is freed instead. */
    fun keep(buffer: PlatformBuffer) {
        if (kept.isEmpty()) kept.add(buffer) else buffer.freeNativeMemory()
    }

    /** The kept buffer when it holds at least [capacity] bytes, else the result of [allocate]. */
    inline fun take(
        capacity: Int,
        allocate: () -> PlatformBuffer,
    ): PlatformBuffer = if (keptFits(capacity)) handOut() else allocate()

    /** Whether a kept buffer holds [capacity] bytes; one too small is freed. */
    @PublishedApi
    internal fun keptFits(capacity: Int): Boolean {
        if (kept.isEmpty()) return false
        if (kept[0].capacity >= capacity) return true
        free()
        return false
    }

    /** The kept buffer, which [keptFits] said is there. */
    @PublishedApi
    internal fun handOut(): PlatformBuffer = kept.removeAt(0)

    /** The connection ended: frees what is kept. */
    fun free() {
        if (kept.isNotEmpty()) kept.removeAt(0).freeNativeMemory()
    }
}
