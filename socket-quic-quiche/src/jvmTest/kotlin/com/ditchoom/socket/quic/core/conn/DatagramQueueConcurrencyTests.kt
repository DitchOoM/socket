package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.network
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Many threads offering to one datagram send queue while one takes from its head and holds what it took, as the
 * connection does while quiche copies a datagram: no datagram is lost, duplicated or torn, each sender's datagrams leave
 * in the order it offered them, an evicting queue evicts no more than it took, a refusal comes only at the bound, and a close
 * while offers run drops and counts what is queued and frees every slot's memory.
 */
class DatagramQueueConcurrencyTests {
    @Test
    fun refusingOffersFromManyThreadsAreEachQueuedOnceOrRefusedAtTheBound() = run(Overflow.RefuseNew)

    @Test
    fun evictingOffersFromManyThreadsAreEachTakenOnceOrEvictedAndAllButNeverRefused() = run(Overflow.EvictOldest)

    @Test
    fun refusingOffersFromManyThreadsWithNoTakerQueueExactlyTheBound() {
        val counts = ConnCounts()
        val queue = queue(Overflow.RefuseNew, counts)
        val queued = AtomicLong()
        val full = AtomicLong()
        val start = CountDownLatch(1)
        val senders =
            List(SENDERS) {
                thread {
                    val buffer = datagram()
                    start.await()
                    repeat(OFFERS / 10) {
                        when (queue.offer(buffer, 0, buffer, SIZE)) {
                            is Offered.Queued -> queued.incrementAndGet()
                            is Offered.Full -> full.incrementAndGet()
                            is Offered.Contended, is Offered.NoMemory, is QueueLife.Closed -> error("nothing contends without a taker")
                        }
                    }
                }
            }
        start.countDown()
        senders.forEach { it.join() }
        assertEquals(DEPTH.toLong(), queued.get(), "the bound is filled exactly: no place lost, none given twice")
        assertEquals(DEPTH, queue.size())
        assertEquals((SENDERS * OFFERS / 10).toLong() - DEPTH, full.get())
        queue.close(ConnEnd.Released)
    }

    @Test
    fun anEvictingOfferWhileTheConnectionHoldsTheOldestEvictsOneAndReturnsOnAnotherThread() {
        val counts = ConnCounts()
        val queue = queue(Overflow.EvictOldest, counts)
        val buffer = datagram()
        repeat(DEPTH) { assertEquals(Offered.Queued, queue.offer(buffer, 0, buffer, SIZE)) }
        val held = assertIs<DatagramSlot>(queue.take())
        assertEquals(Offered.Queued, queue.offer(buffer, 0, buffer, SIZE), "the held one is outside the bound: the queue is full again")
        val answer = AtomicReference<Offered>()
        val done = CountDownLatch(1)
        thread {
            answer.set(queue.offer(buffer, 0, buffer, SIZE))
            done.countDown()
        }
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "the offer returned while the oldest was still held")
        assertEquals(Offered.Queued, answer.get())
        assertEquals(1L, counts.datagramsEvicted, "one eviction, not one per cell the held datagram blocked")
        queue.done(held)
    }

    @Test
    fun aCloseWhileOffersRunDropsAndCountsWhatIsQueuedAndFreesEverySlot() {
        val counts = ConnCounts()
        val queue = queue(Overflow.RefuseNew, counts)
        val queued = AtomicLong()
        val start = CountDownLatch(1)
        val senders =
            List(SENDERS) {
                thread {
                    val buffer = datagram()
                    start.await()
                    repeat(OFFERS) {
                        when (queue.offer(buffer, 0, buffer, SIZE)) {
                            is Offered.Queued -> queued.incrementAndGet()
                            is Offered.Full, is Offered.Contended, is QueueLife.Closed -> Unit
                            is Offered.NoMemory -> error("network memory is made")
                        }
                    }
                }
            }
        start.countDown()
        var taken = 0L
        repeat(TAKES) {
            when (val slot = queue.take()) {
                is DatagramSlot -> {
                    queue.done(slot)
                    taken++
                }
                is Head.Empty -> Unit
            }
        }
        val slots = assertIs<QueueParts.Made>(queue.parts()).slots
        queue.close(ConnEnd.Released)
        senders.forEach { it.join() }
        assertEquals(queued.get(), taken + counts.datagramsDroppedAtClose, "queued = taken + dropped at the close")
        assertEquals(0, slots.holdingMemory(), "every slot's memory is freed once the last offer left")
    }

    private fun run(overflow: Overflow) {
        val counts = ConnCounts()
        val queue = queue(overflow, counts)
        val queued = AtomicLong()
        val full = AtomicLong()
        val contended = AtomicLong()
        val start = CountDownLatch(1)
        val senders =
            List(SENDERS) { sender ->
                thread {
                    val buffer = datagram()
                    start.await()
                    for (n in 0 until OFFERS) {
                        UnsafeMemory.putInt(addressOf(buffer), sender)
                        UnsafeMemory.putInt(addressOf(buffer) + 4, n)
                        when (queue.offer(buffer, 0, buffer, SIZE)) {
                            is Offered.Queued -> queued.incrementAndGet()
                            is Offered.Full -> full.incrementAndGet()
                            is Offered.Contended -> contended.incrementAndGet()
                            is QueueLife.Closed, is Offered.NoMemory -> error("the queue is open and its memory made")
                        }
                    }
                }
            }
        val last = IntArray(SENDERS) { -1 }
        var taken = 0L
        start.countDown()

        fun takeAll() {
            while (true) {
                val slot =
                    when (val head = queue.take()) {
                        is Head.Empty -> return
                        is DatagramSlot -> head
                    }
                val bytes = assertIs<SlotMemory.Made>(slot.memory).bytes
                val sender = UnsafeMemory.getInt(bytes.address)
                val n = UnsafeMemory.getInt(bytes.address + 4)
                assertEquals(SIZE, slot.length)
                assertTrue(n > last[sender], "sender $sender's datagram $n came after ${last[sender]}: each leaves once, in order")
                last[sender] = n
                // Held a moment, as the connection holds it while quiche copies it.
                Thread.onSpinWait()
                queue.done(slot)
                taken++
            }
        }
        while (senders.any { it.isAlive }) takeAll()
        senders.forEach { it.join() }
        takeAll()
        assertEquals((SENDERS * OFFERS).toLong(), queued.get() + full.get() + contended.get(), "attempts = queued + refused")
        assertEquals(queued.get(), taken + counts.datagramsEvicted, "queued = taken + evicted")
        assertTrue(counts.datagramsEvicted <= queued.get(), "never more evictions than datagrams")
        when (overflow) {
            Overflow.RefuseNew -> assertEquals(0L, counts.datagramsEvicted)
            Overflow.EvictOldest -> assertEquals(0L, full.get(), "an evicting queue is never at its bound for a send")
        }
        queue.close(ConnEnd.Released)
    }

    private fun queue(
        overflow: Overflow,
        counts: ConnCounts,
    ) = DatagramQueue(DEPTH, overflow, BufferFactory.network(), VirtualClock(), counts)

    private fun datagram(): PlatformBuffer =
        BufferFactory.network().allocate(SIZE).apply {
            repeat(SIZE) { writeByte(0) }
            resetForRead()
        }

    private fun addressOf(buffer: PlatformBuffer): Long =
        com.ditchoom.socket.quic.core.udp.NativeBytes
            .addressing(buffer, { it }) { error("network memory has an address") }

    private companion object {
        const val DEPTH = 64
        const val SENDERS = 4
        const val OFFERS = 50_000
        const val SIZE = 16
        const val TAKES = 20_000
        const val WAIT_SECONDS = 5L
    }
}
