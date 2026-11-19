package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.nativeMemoryAccess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What `BufferFactory.network()` promises its callers: the requested limit, native memory, reuse, any-thread free. */
class NetworkBufferFactoryTests {
    private val factory = BufferFactory.network()

    @Test
    fun aBufferHasTheRequestedLimitAndNativeMemory() {
        for (size in intArrayOf(0, 1, 11, 64, 65, 1350, 2048, 4097, 65_536, 65_537, 200_000)) {
            val buffer = factory.allocate(size)
            assertEquals(0, buffer.position(), "position for $size")
            assertTrue(buffer.limit() >= size, "limit for $size")
            assertTrue(buffer.capacity >= size, "capacity for $size")
            assertNotNull(buffer.nativeMemoryAccess, "native memory for $size")
            buffer.freeNativeMemory()
        }
    }

    @Test
    fun aFreedBufferIsReusedAndComesBackWithPositionAndLimitReset() {
        val first = factory.allocate(1350)
        val address = first.nativeMemoryAccess!!.nativeAddress
        first.writeInt(7)
        first.resetForRead()
        first.freeNativeMemory()
        val second = factory.allocate(1500)
        assertEquals(address, second.nativeMemoryAccess!!.nativeAddress)
        assertEquals(0, second.position())
        assertEquals(second.capacity, second.limit())
        second.freeNativeMemory()
    }

    @Test
    fun aFreedBufferFailsFastInsteadOfAliasingItsNextOwner() {
        val buffer = factory.allocate(64)
        buffer.freeNativeMemory()
        assertFailsWith<IllegalStateException> { buffer.writeInt(1) }
    }

    @Test
    fun aBufferFreedTwiceIsReturnedOnce() {
        val buffer = factory.allocate(256)
        buffer.freeNativeMemory()
        buffer.freeNativeMemory()
        val a = factory.allocate(256)
        val b = factory.allocate(256)
        assertTrue(a.nativeMemoryAccess!!.nativeAddress != b.nativeMemoryAccess!!.nativeAddress)
        a.freeNativeMemory()
        b.freeNativeMemory()
    }

    @Test
    fun aLittleEndianRequestKeepsItsByteOrder() {
        val buffer = factory.allocate(8, ByteOrder.LITTLE_ENDIAN)
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.byteOrder)
        buffer.freeNativeMemory()
    }

    @Test
    fun aBufferFreedOnAnotherThreadIsReturnedAndNeverHandedOutTwice() {
        val threads = 4
        val rounds = 20_000
        val executor = Executors.newFixedThreadPool(threads * 2)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)
        repeat(threads) { id ->
            executor.execute {
                try {
                    val sizes = intArrayOf(64, 1350, 4096, 65_536)
                    repeat(rounds) { round ->
                        val buffer = factory.allocate(sizes[(id + round) % sizes.size])
                        val stamp = (id * rounds + round).toLong()
                        buffer.writeLong(stamp)
                        Thread.yield()
                        buffer.resetForRead()
                        assertEquals(stamp, buffer.readLong(), "another owner wrote into a live buffer")
                        executor.submit { buffer.freeNativeMemory() }.get()
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(120, TimeUnit.SECONDS))
        executor.shutdown()
        failure.get()?.let { throw it }
    }
}
