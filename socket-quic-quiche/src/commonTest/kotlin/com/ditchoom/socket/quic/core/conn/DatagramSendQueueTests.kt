package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.MaxDatagramSize
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        var refusal = 0
        val taken = ArrayList<List<Int>>()

        init {
            inner.dgramMaxWritableLen = MaxDatagramSize.Bytes(MAX)
        }

        override fun connDgramSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
        ): Int {
            if (refusal != 0) return refusal
            if (room == 0) return DONE
            room--
            taken += List(bufLen) { UnsafeMemory.getByte(buf + it).toInt() and 0xFF }
            return bufLen
        }
    }

    private val quiche = DatagramQuiche()
    private val counts = ConnCounts()
    private val clock = VirtualClock()

    private fun sender(
        depth: Int,
        overflow: Overflow = Overflow.RefuseNew,
        outgoing: AgeBound = AgeBound.Unbounded,
    ): DatagramSender {
        val q = QuicheConnRef(quiche, QuicheConn(1L))
        return DatagramSender(
            q,
            DatagramQueue(depth, overflow, BufferFactory.network(), clock, counts),
            outgoing,
            counts,
        ).also { it.readRoom() }
    }

    /** Offers a datagram of [bytes], one byte each. */
    private fun DatagramSender.offer(vararg bytes: Int): Offered {
        val buffer = bufferOf(*bytes)
        return queue.offer(buffer, 0, buffer, bytes.size).also { buffer.freeIfNeeded() }
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
        val sender = sender(depth = 8, outgoing = AgeBound.Within(Span(AGE)))
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
        assertEquals(2, sender.queue.size(), "the third waits at the door, outside the queue's bound")
        assertEquals(Refill.Nothing, sender.feed(clock.now()), "quiche is still full")
        quiche.room = Int.MAX_VALUE
        sender.quicheWrote()
        sender.feed(clock.now())
        assertEquals(listOf(0, 1, 2, 3, 4), firstBytes(), "the door's datagram goes before the rest, in order")
        assertEquals(5L, sender.output)
    }

    @Test
    fun aRefusalThatEndsTheConnectionCountsTheDatagramAsUnsendable() {
        val sender = sender(depth = 4)
        repeat(3) { sender.offer(it) }
        quiche.refusal = TLS_FAIL
        assertEquals(TLS_FAIL, assertIs<Refill.Refused>(sender.feed(clock.now())).code)
        assertEquals(1L, counts.datagramsDroppedUnsendable, "the refused datagram is counted, not lost")
        assertEquals(2, sender.queue.size(), "the rest wait for the close point, which drops and counts them")
        sender.close(ConnEnd.Released)
        assertEquals(2L, counts.datagramsDroppedAtClose)
    }

    @Test
    fun aPrefixAndAPayloadGoAsOneDatagramPrefixFirst() {
        val sender = sender(depth = 2)
        val prefix = bufferOf(0x40, 0x01)
        val payload = heapBufferOf(7, 8, 9)
        assertEquals(Offered.Queued, sender.queue.offer(prefix, 2, payload, 3))
        assertEquals(0, payload.position(), "the payload is the caller's again, unmoved")
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
            assertEquals(6, sender.queue.size(), "one taken, the next at the door")
            assertEquals(emptyList(), woken, "a free slot is not room: the queue is above its low-water mark")
            quiche.room = 3
            sender.quicheWrote()
            sender.feed(clock.now())
            yield()
            assertEquals(3, sender.queue.size(), "the door's and two more went; the next waits at the door")
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
            sender.close(ConnEnd.Released)
            yield()
            assertEquals(listOf(0), woken, "a waiter goes on when the connection ends")
            assertIs<QueueLife.Closed>(sender.offer(5))
            assertEquals(2L, counts.datagramsDroppedAtClose, "what was queued at the close point is dropped and counted")
            assertEquals(0, sender.queue.size(), "a closed queue counts nothing as admitted")
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

    @Test
    fun evictingWhileTheConnectionHoldsTheOldestEvictsOneAndNeverWaits() {
        val sender = sender(depth = 4, overflow = Overflow.EvictOldest)
        repeat(4) { sender.offer(it) }
        val held = assertIs<DatagramSlot>(sender.queue.take(), "the connection holds the oldest, as it does while quiche copies it")
        assertEquals(Offered.Queued, sender.offer(8), "the held datagram is outside the bound: a place is free")
        assertEquals(0L, counts.datagramsEvicted)
        assertEquals(Offered.Queued, sender.offer(9), "the full queue takes the new datagram at once")
        assertEquals(1L, counts.datagramsEvicted, "one eviction: the oldest the connection does not hold")
        sender.queue.done(held)
        sender.feed(clock.now())
        assertEquals(listOf(2, 3, 8, 9), firstBytes())
    }

    @Test
    fun anEvictingQueueOfOneTakesASendWhileTheConnectionHoldsItsDatagram() {
        val sender = sender(depth = 1, overflow = Overflow.EvictOldest)
        assertEquals(Offered.Queued, sender.offer(0))
        val held = assertIs<DatagramSlot>(sender.queue.take())
        assertEquals(Offered.Queued, sender.offer(1), "the held datagram, at the door or being copied, is outside the bound")
        assertEquals(0L, counts.datagramsEvicted)
        assertEquals(Offered.Queued, sender.offer(2), "a queue at its bound evicts its one queued datagram")
        assertEquals(1L, counts.datagramsEvicted)
        assertEquals(1, sender.queue.size())
        sender.queue.done(held)
    }

    @Test
    fun aRefusingQueueCountsADatagramUntilTheConnectionTakesIt() {
        val sender = sender(depth = 4)
        repeat(4) { sender.offer(it) }
        assertEquals(Offered.Full, sender.offer(8), "four queued: the bound")
        val held = assertIs<DatagramSlot>(sender.queue.take())
        assertEquals(Offered.Queued, sender.offer(9), "the taken one left the bound, so there is room, and no refusal is spurious")
        assertEquals(Offered.Full, sender.offer(10), "and the bound holds again")
        sender.queue.done(held)
    }

    @Test
    fun aFeedWhileQuicheIsFullStillWakesWaitersOnceTheQueueIsLow() =
        runBlocking {
            val sender = sender(depth = 8)
            repeat(8) { sender.offer(it) }
            quiche.room = 1
            sender.feed(clock.now())
            val woken = ArrayList<Int>()
            val waiter = wait(sender) { woken += 0 }
            // The queue falls to its low-water mark by a way other than this feed's own takes.
            repeat(3) { sender.queue.done(assertIs<DatagramSlot>(sender.queue.take())) }
            assertEquals(3, sender.queue.size())
            assertEquals(Refill.Nothing, sender.feed(clock.now()), "quiche is full and has written nothing: it is not asked")
            yield()
            assertEquals(listOf(0), woken, "a feed that returns early still wakes the waiters of a low queue")
            waiter.join()
        }

    @Test
    fun aFeedOfAClosedQueueWakesWaitersOnceItIsLow() =
        runBlocking {
            val sender = sender(depth = 8)
            repeat(8) { sender.offer(it) }
            val woken = ArrayList<Int>()
            val waiter = wait(sender) { woken += 0 }
            repeat(5) { sender.queue.done(assertIs<DatagramSlot>(sender.queue.take())) }
            sender.queue.close(ConnEnd.Released)
            sender.feed(clock.now())
            yield()
            assertEquals(listOf(0), woken)
            waiter.join()
        }

    @Test
    fun aQueueThatIsNeverOfferedADatagramMakesNoRingAndNoMemory() {
        val sender = sender(depth = 1024)
        sender.feed(clock.now())
        sender.trim(clock.now())
        assertIs<QueueParts.Unmade>(sender.queue.parts())
    }

    @Test
    fun aSteadyOccupancyOfOneKeepsTheMemoryOfAFewSlotsAfterManySends() {
        val sender = sender(depth = 1024)
        repeat(SENDS) {
            sender.offer(it and 0xFF)
            clock.at = Nanos(clock.at.value + STEP)
            sender.feed(clock.now())
            sender.trim(clock.now())
        }
        val slots = assertIs<QueueParts.Made>(sender.queue.parts()).slots
        assertTrue(slots.made() <= 2, "one slot in use at a time: ${slots.made()} made")
        assertTrue(slots.keptWithMemory() <= 4, "a few kept: ${slots.keptWithMemory()}")
    }

    @Test
    fun aQueueThatWasFullOnceLetsTheMemoryGoOnceItHasBeenLowForAWhile() {
        val sender = sender(depth = 256)
        repeat(256) { sender.offer(it and 0xFF) }
        sender.feed(clock.now())
        repeat(3) {
            clock.at = Nanos(clock.at.value + WINDOW)
            sender.offer(1)
            sender.feed(clock.now())
            sender.trim(clock.now())
        }
        val slots = assertIs<QueueParts.Made>(sender.queue.parts()).slots
        assertTrue(slots.keptWithMemory() <= 4, "memory follows occupancy: ${slots.keptWithMemory()} slots keep memory")
    }

    @Test
    fun aFactoryThatThrowsLeavesTheQueueWorking() {
        val failing = FailingFactory()
        val q = QuicheConnRef(quiche, QuicheConn(1L))
        val sender =
            DatagramSender(q, DatagramQueue(4, Overflow.RefuseNew, failing, clock, counts), AgeBound.Unbounded, counts)
                .also { it.readRoom() }
        failing.failing = true
        val refused = assertIs<Offered.NoMemory>(sender.offer(1), "the factory's failure is the offer's answer, not a throw")
        assertIs<IllegalStateException>(refused.failure)
        assertEquals(0, sender.queue.size(), "a send whose memory failed holds no place")
        failing.failing = false
        repeat(4) { assertEquals(Offered.Queued, sender.offer(it), "the queue takes datagrams after the failure") }
        sender.feed(clock.now())
        assertEquals(listOf(0, 1, 2, 3), firstBytes())
        val slots = assertIs<QueueParts.Made>(sender.queue.parts()).slots
        sender.close(ConnEnd.Released)
        assertEquals(0, slots.holdingMemory(), "every slot's memory was freed at the close")
    }

    /** Makes network buffers, or throws while [failing]. */
    private class FailingFactory : BufferFactory by BufferFactory.network() {
        var failing = false

        override fun allocate(
            size: Int,
            byteOrder: com.ditchoom.buffer.ByteOrder,
        ): PlatformBuffer {
            check(!failing) { "the factory fails" }
            return BufferFactory.network().allocate(size, byteOrder)
        }
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

    private fun bufferOf(vararg bytes: Int): PlatformBuffer =
        BufferFactory.network().allocate(maxOf(bytes.size, 1)).apply {
            bytes.forEach { writeByte(it.toByte()) }
            resetForRead()
        }

    private fun heapBufferOf(vararg bytes: Int): PlatformBuffer =
        BufferFactory.managed().allocate(bytes.size).apply {
            bytes.forEach { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val MAX = 1200
        const val DONE = -1
        const val TLS_FAIL = -10
        const val AGE = 10_000_000L
        const val SENDS = 10_000
        const val STEP = 1_000_000L
        const val WINDOW = 1_100_000_000L
    }
}
