package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.socket.quic.MaxDatagramSize
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A connection's datagram send queue and the sender that feeds quiche from its head, on a stub quiche that takes as
 * many datagrams as the test lets it: refusal and eviction at the high-water mark, the outgoing maximum age, the door,
 * and callers waiting for room, woken in order at the low-water mark.
 */
class DatagramSendQueueTests {
    /** A quiche whose datagram queue takes [room] more datagrams; records the first byte of each. */
    private class DatagramQuiche(
        private val inner: StubQuicheApi = StubQuicheApi(),
    ) : QuicheApi by inner {
        var room = Int.MAX_VALUE
        val taken = ArrayList<List<Int>>()

        init {
            inner.dgramMaxWritableLen = MaxDatagramSize.Bytes(MAX)
        }

        override fun connDgramSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
        ): Int {
            if (room == 0) return DONE
            room--
            taken += List(bufLen) { UnsafeMemory.getByte(buf + it).toInt() and 0xFF }
            return bufLen
        }
    }

    private val quiche = DatagramQuiche()
    private val counts = ConnCounts()
    private val clock = VirtualClock()
    private val memory = nativeBytes(MAX)

    private fun sender(
        depth: Int,
        overflow: Overflow = Overflow.RefuseNew,
        outgoing: AgeBound = AgeBound.Unbounded,
    ): DatagramSender {
        val q = QuicheConnRef(quiche, QuicheConn(1L))
        return DatagramSender(
            q,
            DatagramQueue(depth, overflow, outgoing, BufferFactory.network(), clock, counts),
            outgoing,
            BufferFactory.network(),
            counts,
        ).also { it.readRoom() }
    }

    /** Offers a datagram of [bytes], one byte each, through the test's memory. */
    private fun DatagramSender.offer(vararg bytes: Int): Offered {
        bytes.forEachIndexed { at, byte -> UnsafeMemory.putByte(memory.address + at, byte.toByte()) }
        return queue.offer(0L, 0, memory.address, bytes.size)
    }

    private fun firstBytes(): List<Int> = quiche.taken.map { it.first() }

    @Test
    fun aQueueAtItsHighWaterMarkRefusesTheNewDatagramAndKeepsTheQueuedOnes() {
        val sender = sender(depth = 4)
        repeat(4) { assertEquals(Offered.Queued, sender.offer(it)) }
        assertEquals(Offered.Full, sender.offer(9), "the fifth is refused")
        assertEquals(4, sender.queue.size())
        sender.feed(clock.now())
        assertEquals(listOf(0, 1, 2, 3), firstBytes(), "the queued ones go, oldest first")
        assertEquals(Offered.Queued, sender.offer(4), "and the queue takes more")
    }

    @Test
    fun evictingTheOldestKeepsTheFreshestDatagramsAndCountsEachEviction() {
        val sender = sender(depth = 4, overflow = Overflow.EvictOldest)
        repeat(10) { assertEquals(Offered.Queued, sender.offer(it), "an evicting queue takes every send") }
        assertEquals(6L, counts.datagramsEvicted)
        sender.feed(clock.now())
        assertEquals(listOf(6, 7, 8, 9), firstBytes(), "the four freshest are kept, in order")
    }

    @Test
    fun aDatagramPastItsOutgoingAgeIsDroppedWhenItReachesTheHeadAndCounted() {
        val sender = sender(depth = 8, outgoing = AgeBound.Within(AGE))
        sender.offer(1)
        clock.at = Nanos(AGE - 2)
        sender.offer(2)
        clock.at = Nanos(AGE + 1)
        sender.feed(clock.now())
        assertEquals(listOf(2), firstBytes(), "the older one expired at the head; the younger went")
        assertEquals(1L, counts.datagramsExpiredOutgoing)
    }

    @Test
    fun theDatagramQuicheRefusedForAFullQueueGoesFirstOnceItHasRoom() {
        val sender = sender(depth = 8)
        repeat(5) { sender.offer(it) }
        quiche.room = 2
        assertEquals(Refill.Moved, sender.feed(clock.now()))
        assertEquals(listOf(0, 1), firstBytes())
        assertEquals(2, sender.queue.size(), "the third waits at the door, out of the queue")
        assertEquals(Refill.Nothing, sender.feed(clock.now()), "quiche is still full")
        quiche.room = Int.MAX_VALUE
        sender.feed(clock.now())
        assertEquals(listOf(0, 1, 2, 3, 4), firstBytes(), "the door's datagram goes before the rest, in order")
        assertEquals(5L, sender.output)
    }

    @Test
    fun aPrefixAndAPayloadGoAsOneDatagramPrefixFirst() {
        val sender = sender(depth = 2)
        val prefix =
            nativeBytes(2).also {
                UnsafeMemory.putByte(it.address, 0x40)
                UnsafeMemory.putByte(it.address + 1, 0x01)
            }
        repeat(3) { UnsafeMemory.putByte(memory.address + it, (it + 7).toByte()) }
        assertEquals(Offered.Queued, sender.queue.offer(prefix.address, 2, memory.address, 3))
        sender.feed(clock.now())
        assertEquals(listOf(listOf(0x40, 0x01, 7, 8, 9)), quiche.taken)
    }

    @Test
    fun waitersAreWokenInArrivalOrderAtTheLowWaterMarkNotForOneFreeSlot() =
        runBlocking {
            val sender = sender(depth = 8)
            repeat(8) { sender.offer(it) }
            val woken = ArrayList<Int>()
            val waiters = List(3) { n -> wait(sender) { woken += n } }
            quiche.room = 1
            sender.feed(clock.now())
            yield()
            assertEquals(6, sender.queue.size(), "one taken, one at the door")
            assertEquals(emptyList(), woken, "a free slot is not room: the queue is above its low-water mark")
            quiche.room = 2
            sender.feed(clock.now())
            yield()
            assertEquals(4, sender.queue.size())
            assertEquals(listOf(0, 1, 2), woken, "at the low-water mark every waiter goes, in the order it began to wait")
            waiters.forEach { it.join() }
        }

    @Test
    fun aCancelledWaiterTakesNoWakeFromTheOthers() =
        runBlocking {
            val sender = sender(depth = 4)
            repeat(4) { sender.offer(it) }
            val woken = ArrayList<Int>()
            val waiters = List(3) { n -> wait(sender) { woken += n } }
            waiters[0].cancel()
            yield()
            sender.feed(clock.now())
            yield()
            assertEquals(listOf(1, 2), woken, "the cancelled first waiter is passed over and the others still go")
            waiters.forEach { it.join() }
        }

    @Test
    fun aWithdrawnWaiterIsNotAnsweredAndTheOthersAre() =
        runBlocking {
            val sender = sender(depth = 4)
            repeat(4) { sender.offer(it) }
            val woken = ArrayList<Int>()
            val waits = ArrayList<RoomWait>()
            val waiters = List(3) { n -> wait(sender, waits) { woken += n } }
            waiters[1].cancel()
            sender.withdraw(waits[1])
            sender.feed(clock.now())
            yield()
            assertEquals(listOf(0, 2), woken)
            waiters.forEach { it.join() }
        }

    @Test
    fun afterTheEndTheQueueTakesNothingAndEveryWaiterGoesOn() =
        runBlocking {
            val sender = sender(depth = 2)
            repeat(2) { sender.offer(it) }
            val woken = ArrayList<Int>()
            val waiter = wait(sender) { woken += 0 }
            quiche.room = 0
            sender.endAll()
            yield()
            assertEquals(listOf(0), woken, "a waiter goes on when the connection ends")
            assertEquals(Offered.Closed, sender.offer(5))
            waiter.join()
        }

    @Test
    fun aWaiterWhenTheQueueIsAlreadyLowGoesOnAtOnce() =
        runBlocking {
            val sender = sender(depth = 8)
            repeat(4) { sender.offer(it) }
            val woken = ArrayList<Int>()
            wait(sender) { woken += 0 }.join()
            assertEquals(listOf(0), woken)
        }

    /** A caller parked in [sender] the way a [RoomWait] parks it inside a connection; runs [then] once woken. */
    private fun kotlinx.coroutines.CoroutineScope.wait(
        sender: DatagramSender,
        made: MutableList<RoomWait> = ArrayList(),
        then: () -> Unit,
    ): Job =
        launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { caller ->
                val wait = RoomWait(caller)
                made += wait
                sender.awaitRoom(wait)
            }
            then()
        }

    private companion object {
        const val MAX = 1200
        const val DONE = -1
        const val AGE = 10_000_000L
    }
}
