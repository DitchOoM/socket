package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile
import kotlin.test.assertEquals

/**
 * A [BufferFactory] wrapper that tracks every allocation and free.
 * Use [assertNoLeaks] after a test to verify all deterministic buffers were freed.
 *
 * Only tracks buffers from [allocate] — buffers from [wrap] are heap-backed
 * and don't need explicit freeing.
 *
 * **Thread-safe.** Allocations reach this factory from more than one thread at once — e.g. a
 * datagram receiver acquiring a pool leaf on the caller's coroutine while the connection driver
 * allocates scratch on its own loop. The original index-keyed `MutableList` + `MutableSet`
 * bookkeeping raced under that concurrency (two racing `allocate` calls could compute the same
 * index, so one buffer's free marked the other's slot and a genuinely-freed buffer reported as
 * leaked). Tracking is now a per-buffer `@Volatile` flag, with a spin-lock guarding only the
 * allocation list (a [Mutex] via `tryLock` — `allocate` is not suspending, and the critical
 * section is a single list append).
 */
class TrackingBufferFactory(
    private val delegate: BufferFactory = BufferFactory.deterministic(),
) : BufferFactory {
    private val listLock = Mutex()
    private val allocated = mutableListOf<TrackedBuffer>()

    private inline fun <T> locked(block: () -> T): T {
        while (!listLock.tryLock()) {
            // Spin: the critical section is a single list append/snapshot, contention is rare.
        }
        try {
            return block()
        } finally {
            listLock.unlock()
        }
    }

    override fun allocate(
        size: Int,
        byteOrder: com.ditchoom.buffer.ByteOrder,
    ): PlatformBuffer {
        val buffer = delegate.allocate(size, byteOrder)
        val tracked = TrackedBuffer(buffer, Throwable("Allocated at"))
        locked { allocated.add(tracked) }
        return TrackingPlatformBuffer(buffer, tracked)
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: com.ditchoom.buffer.ByteOrder,
    ): PlatformBuffer = delegate.wrap(array, byteOrder)

    /** Number of buffers currently alive (allocated but not freed). */
    val liveCount: Int get() = locked { allocated.count { !it.freed } }

    /** Assert that every allocated buffer has been freed. Fails with allocation stack traces. */
    fun assertNoLeaks() {
        val leaked = locked { allocated.withIndex().filter { !it.value.freed } }
        if (leaked.isEmpty()) return

        val details =
            leaked.joinToString("\n") { (i, trace) ->
                "  Buffer #$i (${trace.buffer})\n    ${trace.allocationSite.stackTraceToString().lines().take(5).joinToString("\n    ")}"
            }
        throw AssertionError("${leaked.size} buffer(s) leaked:\n$details")
    }

    /** Assert exact number of live buffers. */
    fun assertLiveCount(expected: Int) {
        assertEquals(expected, liveCount, "Expected $expected live buffers, got $liveCount")
    }

    internal class TrackedBuffer(
        val buffer: PlatformBuffer,
        val allocationSite: Throwable,
    ) {
        @Volatile
        var freed: Boolean = false
    }
}

/**
 * Wrapper that intercepts [freeNativeMemory] to track buffer lifecycle.
 * Delegates all other operations to the underlying [PlatformBuffer].
 */
private class TrackingPlatformBuffer(
    private val delegate: PlatformBuffer,
    private val tracked: TrackingBufferFactory.TrackedBuffer,
) : PlatformBuffer by delegate,
    com.ditchoom.buffer.CloseableBuffer {
    override val isFreed: Boolean get() = tracked.freed

    override fun freeNativeMemory() {
        if (tracked.freed) return // idempotent
        tracked.freed = true
        delegate.freeNativeMemory()
    }
}
