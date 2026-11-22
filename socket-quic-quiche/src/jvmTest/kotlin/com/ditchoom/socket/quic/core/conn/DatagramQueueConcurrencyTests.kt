package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.network
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many threads offering to one datagram send queue while one takes from its head: no datagram is lost, duplicated or
 * torn, each sender's datagrams leave in the order it offered them, and every offer is accounted for.
 */
class DatagramQueueConcurrencyTests {
    @Test
    fun refusingOffersFromManyThreadsAreEachQueuedOnceOrRefused() = run(Overflow.RefuseNew)

    @Test
    fun evictingOffersFromManyThreadsAreEachTakenOnceOrEvicted() = run(Overflow.EvictOldest)

    private fun run(overflow: Overflow) {
        val counts = ConnCounts()
        val queue = DatagramQueue(DEPTH, overflow, AgeBound.Unbounded, BufferFactory.network(), VirtualClock(), counts)
        val queued = AtomicLong()
        val full = AtomicLong()
        val start = CountDownLatch(1)
        val senders =
            List(SENDERS) { sender ->
                thread {
                    val memory = nativeBytes(SIZE)
                    start.await()
                    for (n in 0 until OFFERS) {
                        UnsafeMemory.putInt(memory.address, sender)
                        UnsafeMemory.putInt(memory.address + 4, n)
                        when (queue.offer(0L, 0, memory.address, SIZE)) {
                            Offered.Queued -> queued.incrementAndGet()
                            Offered.Full -> full.incrementAndGet()
                            Offered.Closed, Offered.Unaddressable -> error("the queue is open and its memory native")
                        }
                    }
                }
            }
        val last = IntArray(SENDERS) { -1 }
        var taken = 0L
        start.countDown()

        fun takeAll() {
            while (true) {
                val cell =
                    when (val head = queue.take()) {
                        is Head.Empty -> return
                        is QueuedDatagram -> head
                    }
                val bytes = (cell.memory as CellMemory.Made).bytes
                val sender = UnsafeMemory.getInt(bytes.address)
                val n = UnsafeMemory.getInt(bytes.address + 4)
                assertEquals(SIZE, cell.length)
                assertTrue(n > last[sender], "sender $sender's datagram $n came after ${last[sender]}: each leaves once, in order")
                last[sender] = n
                queue.release(cell)
                taken++
            }
        }
        while (senders.any { it.isAlive }) takeAll()
        senders.forEach { it.join() }
        takeAll()
        assertEquals((SENDERS * OFFERS).toLong(), queued.get() + full.get(), "attempts = queued + refused")
        assertEquals(queued.get(), taken + counts.datagramsEvicted, "queued = taken + evicted")
        when (overflow) {
            Overflow.RefuseNew -> assertEquals(0L, counts.datagramsEvicted)
            Overflow.EvictOldest -> assertEquals(0L, full.get(), "an evicting queue refuses nothing")
        }
        queue.close()
    }

    private companion object {
        const val DEPTH = 64
        const val SENDERS = 4
        const val OFFERS = 50_000
        const val SIZE = 16
    }
}
