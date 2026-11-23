package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.network
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A queue's memory once the connection closed it while an offer was still inside: the last offer out frees every slot,
 * on its own thread, and the connection's trim frees nothing more, so each buffer is freed exactly once.
 */
class DatagramQueueCloseMemoryTests {
    @Test
    fun aTrimAfterTheCloseFreesNothingTheLastOfferOutFrees() {
        val factory = GatedFactory()
        val counts = ConnCounts()
        val queue = DatagramQueue(BOUND, Overflow.RefuseNew, factory, VirtualClock(), counts)
        val small = datagram(SMALL)
        repeat(2) { assertEquals(Offered.Queued, queue.offer(small, 0, small, SMALL)) }
        repeat(2) {
            val slot = queue.take() as DatagramSlot
            queue.done(slot)
            queue.left(1)
        }
        // Two free slots keep memory. The next offer takes one, outgrows it, and blocks inside while its memory is made.
        factory.blockNextAllocation()
        val size = factory.made.maxOf { it.capacity } + 1
        val large = datagram(size)
        val offer = thread { queue.offer(large, 0, large, size) }
        assertTrue(factory.allocating.await(WAIT_SECONDS, TimeUnit.SECONDS), "the offer is inside, making its memory")
        val loop =
            thread(start = false) {
                queue.close(ConnEnd.Released)
                queue.trim(0)
            }
        factory.loop.set(loop)
        loop.start()
        awaitEither(loop, factory.loopFreeing)
        factory.releaseAllocation()
        offer.join(WAIT_SECONDS * MILLIS)
        factory.releaseLoopFree()
        loop.join(WAIT_SECONDS * MILLIS)
        val buffers = factory.made.toList()
        assertTrue(buffers.isNotEmpty())
        assertEquals(buffers.map { 1 }, buffers.map { it.frees.get() }, "each buffer freed exactly once: $buffers")
        assertEquals(1L, counts.datagramsDroppedAtClose, "the datagram the last offer queued is dropped at the close")
        small.freeNativeMemory()
        large.freeNativeMemory()
    }

    /** Returns once [loop] finished or, in a trim that frees, is inside a free. */
    private fun awaitEither(
        loop: Thread,
        freeing: CountDownLatch,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (loop.isAlive && freeing.count > 0 && System.nanoTime() < deadline) Thread.onSpinWait()
    }

    private fun datagram(size: Int): PlatformBuffer =
        BufferFactory.network().allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    /**
     * Network buffers that count their frees; the next allocation can be held, and a free on the [loop] thread is held,
     * so a test can stop either side inside the queue. Only a buffer's first free releases its memory.
     */
    private class GatedFactory : BufferFactory by BufferFactory.network() {
        val made = ConcurrentLinkedQueue<Counted>()
        val allocating = CountDownLatch(1)
        val loopFreeing = CountDownLatch(1)
        val loop = AtomicReference<Thread>()
        private val armed = AtomicInteger(0)
        private val allocationGate = CountDownLatch(1)
        private val freeGate = CountDownLatch(1)

        fun blockNextAllocation() = armed.set(1)

        fun releaseAllocation() = allocationGate.countDown()

        fun releaseLoopFree() = freeGate.countDown()

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            if (armed.compareAndSet(1, 0)) {
                allocating.countDown()
                allocationGate.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
            return Counted(BufferFactory.network().allocate(size, byteOrder)).also { made.add(it) }
        }

        inner class Counted(
            private val inner: PlatformBuffer,
        ) : PlatformBuffer by inner {
            val frees = AtomicInteger()

            override fun freeNativeMemory() {
                if (Thread.currentThread() === loop.get()) {
                    loopFreeing.countDown()
                    freeGate.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
                if (frees.incrementAndGet() == 1) inner.freeNativeMemory()
            }

            override fun toString(): String = "${inner.capacity} B freed ${frees.get()}x"
        }
    }

    private companion object {
        const val BOUND = 4
        const val SMALL = 16
        const val WAIT_SECONDS = 5L
        const val MILLIS = 1_000L
    }
}
