package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic proof for the `ReadBufferPoolingTest` CI flake ("expected < 20 allocations, got 253
 * over 380 reads"). The JVM read path requests a buffer the size of `SO_RCVBUF`, which Linux autotunes
 * to values like 2.5 MB under load. The underlying pool allocated such a request as a 3 MB buffer and
 * filed it where a 2.5 MB request never looks, so every read allocated. No timing is involved: the same
 * acquire-then-free loop misses on every iteration for those sizes.
 */
class ReadBufferSourceReuseTest {
    private class CountingFactory(
        private val delegate: BufferFactory = BufferFactory.Default,
    ) : BufferFactory by delegate {
        val allocations = AtomicInteger(0)

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            allocations.incrementAndGet()
            return delegate.allocate(size, byteOrder)
        }
    }

    @Test
    fun releasedBufferIsReusedForAnySocketReceiveBufferSize() {
        val sizes = listOf(65_536, 131_072, 212_992, 1_048_576, 1_100_000, 2_500_000, 3_000_000, 6_291_456)
        for (size in sizes) {
            val factory = CountingFactory()
            val source = ReadBufferSource(TransportConfig(bufferFactory = factory))
            repeat(50) { source.acquire(size).freeNativeMemory() }
            assertEquals(1, factory.allocations.get(), "request of $size bytes must reuse its released buffer")
        }
    }

    @Test
    fun acquiredBufferIsAtLeastTheRequestedSize() {
        val source = ReadBufferSource(TransportConfig(bufferFactory = CountingFactory()))
        for (size in listOf(1, 1000, 131_072, 2_500_000, 6_291_456)) {
            val buffer = source.acquire(size)
            assertTrue(buffer.capacity >= size, "capacity ${buffer.capacity} < requested $size")
            buffer.freeNativeMemory()
        }
    }
}
