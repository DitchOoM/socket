package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import kotlin.test.assertEquals

/**
 * A [BufferFactory] wrapper that tracks every allocation and free.
 * Use [assertNoLeaks] after a test to verify all deterministic buffers were freed.
 *
 * Only tracks buffers from [allocate] — buffers from [wrap] are heap-backed
 * and don't need explicit freeing.
 */
class TrackingBufferFactory(
    private val delegate: BufferFactory = BufferFactory.deterministic(),
) : BufferFactory {
    private val allocated = mutableListOf<TrackedBuffer>()
    private val freed = mutableSetOf<Int>() // indices into allocated

    override fun allocate(
        size: Int,
        byteOrder: com.ditchoom.buffer.ByteOrder,
    ): PlatformBuffer {
        val buffer = delegate.allocate(size, byteOrder)
        val index = allocated.size
        allocated.add(TrackedBuffer(index, buffer, Throwable("Allocated at")))
        return TrackingPlatformBuffer(buffer, index, this)
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: com.ditchoom.buffer.ByteOrder,
    ): PlatformBuffer = delegate.wrap(array, byteOrder)

    internal fun markFreed(index: Int) {
        freed.add(index)
    }

    /** Number of buffers currently alive (allocated but not freed). */
    val liveCount: Int get() = allocated.size - freed.size

    /** Assert that every allocated buffer has been freed. Fails with allocation stack traces. */
    fun assertNoLeaks() {
        val leaked = allocated.indices.filter { it !in freed }
        if (leaked.isEmpty()) return

        val details =
            leaked.joinToString("\n") { i ->
                val trace = allocated[i]
                "  Buffer #$i (${trace.buffer})\n    ${trace.allocationSite.stackTraceToString().lines().take(5).joinToString("\n    ")}"
            }
        throw AssertionError("${leaked.size} buffer(s) leaked:\n$details")
    }

    /** Assert exact number of live buffers. */
    fun assertLiveCount(expected: Int) {
        assertEquals(expected, liveCount, "Expected $expected live buffers, got $liveCount")
    }

    private data class TrackedBuffer(
        val index: Int,
        val buffer: PlatformBuffer,
        val allocationSite: Throwable,
    )
}

/**
 * Wrapper that intercepts [freeNativeMemory] to track buffer lifecycle.
 * Delegates all other operations to the underlying [PlatformBuffer].
 */
private class TrackingPlatformBuffer(
    private val delegate: PlatformBuffer,
    private val index: Int,
    private val factory: TrackingBufferFactory,
) : PlatformBuffer by delegate,
    com.ditchoom.buffer.CloseableBuffer {
    private var freed = false

    override val isFreed: Boolean get() = freed

    override fun freeNativeMemory() {
        if (freed) return // idempotent
        freed = true
        factory.markFreed(index)
        delegate.freeNativeMemory()
    }
}
