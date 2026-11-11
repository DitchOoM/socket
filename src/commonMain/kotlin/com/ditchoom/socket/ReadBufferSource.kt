package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode

/**
 * The single, per-connection source of socket **read** buffers.
 *
 * Every platform `readRaw` that allocates its receive buffer from [TransportConfig.bufferFactory] (JVM
 * NIO/NIO2 and Linux io_uring; Apple wraps `NSData` zero-copy and Node returns V8-managed buffers, so
 * neither uses this) draws from here instead of calling `config.bufferFactory.allocate(...)` directly.
 * This removes the duplicated `BufferPool(factory = config.bufferFactory)` wiring and, more importantly,
 * makes receive-buffer reclamation **deterministic**:
 *
 * A fresh `Arena.ofAuto()` buffer allocated per read is reclaimed only by the GC Cleaner. Under high
 * read throughput (e.g. large permessage-deflate messages) those buffers accumulate to the JVM's direct
 * memory cap (`-XX:MaxDirectMemorySize`, which defaults to `-Xmx`) faster than the Cleaner frees them,
 * producing an intermittent `OutOfMemoryError: Cannot reserve … direct buffer memory`. Pooling instead
 * reuses one buffer: the consumer returns it by calling [PlatformBuffer.freeNativeMemory] once it has
 * consumed the bytes — which the codec `StreamProcessor` already does automatically ("takes ownership
 * and frees PlatformBuffers when consumed") — and that returns the buffer to this pool rather than
 * leaving it for the GC. Zero-copy is preserved: the buffer is still handed straight to the consumer.
 *
 * [ThreadingMode.MultiThreaded]: a buffer is acquired on the read coroutine but freed on the consumer
 * coroutine, which may run on a different thread.
 */
class ReadBufferSource(
    config: TransportConfig,
) {
    private val pool: BufferPool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            factory = config.bufferFactory,
        )

    /**
     * Acquire a read buffer of at least [minSize] bytes, reused from the pool when one is available.
     * The buffer is write-ready. Whoever takes ownership of it must return it to the pool via
     * [PlatformBuffer.freeNativeMemory] when finished (the codec stream does this on consume).
     */
    fun acquire(minSize: Int): PlatformBuffer = pool.acquire(poolFriendlySize(minSize)) as PlatformBuffer

    internal companion object {
        private const val MAX_POWER_OF_TWO = 1 shl 30

        /**
         * Rounds [minSize] up to a power of two. The pool buckets by power of two, but when it allocates a
         * request above 1 MiB it rounds to a 1 MiB multiple (2.5 MB becomes 3 MB). That 3 MB buffer files
         * under a smaller bucket than the 2.5 MB request searches, so it is never found again and every
         * read allocates. A power-of-two request allocates exactly that size and is found on the next
         * acquire. The JVM read path asks for the socket's `SO_RCVBUF`, which Linux autotunes to arbitrary
         * values above 1 MiB under sustained load.
         */
        internal fun poolFriendlySize(minSize: Int): Int =
            when {
                minSize <= 1 -> 1
                minSize > MAX_POWER_OF_TWO -> minSize // no larger Int power of two; never hand back less than asked
                else -> 1 shl (Int.SIZE_BITS - (minSize - 1).countLeadingZeroBits())
            }
    }
}
