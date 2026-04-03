package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PooledBufferFactoryTests {
    @Test
    fun allocateReturnsBufferFromPool() {
        val pool = BufferPool()
        val factory = PooledBufferFactory(pool, BufferFactory.Default)

        val buffer = factory.allocate(64)
        assertTrue(buffer.capacity >= 64)

        // Writing and reading should work
        buffer.writeInt(42)
        buffer.resetForRead()
        assertEquals(42, buffer.readInt())
    }

    @Test
    fun allocateReusesBuffersAfterFree() {
        val pool = BufferPool()
        val factory = PooledBufferFactory(pool, BufferFactory.Default)

        // First allocation
        val buffer1 = factory.allocate(64)
        buffer1.writeInt(1)
        // Return to pool
        (buffer1 as PlatformBuffer).freeNativeMemory()

        val stats1 = pool.stats()
        assertEquals(1L, stats1.totalAllocations)

        // Second allocation should reuse
        val buffer2 = factory.allocate(64)
        buffer2.writeInt(2)
        (buffer2 as PlatformBuffer).freeNativeMemory()

        val stats2 = pool.stats()
        assertEquals(2L, stats2.totalAllocations)
        assertTrue(stats2.poolHits > 0, "Expected pool reuse but hitRate was ${stats2.hitRate}")
    }

    @Test
    fun wrapDelegatesToUnderlyingFactory() {
        val pool = BufferPool()
        val factory = PooledBufferFactory(pool, BufferFactory.Default)

        val bytes = byteArrayOf(1, 2, 3, 4)
        val buffer = factory.wrap(bytes)

        // wrap() creates a buffer with position=0, limit=capacity, ready to read
        assertEquals(1, buffer.readByte())
        assertEquals(2, buffer.readByte())

        // Pool should not have been used for wrap
        assertEquals(0L, pool.stats().totalAllocations)
    }

    @Test
    fun allocateMinimumSizeIsOne() {
        val pool = BufferPool()
        val factory = PooledBufferFactory(pool, BufferFactory.Default)

        // Should not throw even for size 0 (maxOf(0, 1) = 1)
        val buffer = factory.allocate(0)
        assertTrue(buffer.capacity >= 1)
    }
}
