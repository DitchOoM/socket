package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.LincheckBudget
import com.ditchoom.socket.quic.MaxDatagramSize
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test

/**
 * [DatagramQueue]'s lock-free protocol, model checked as senders and the connection use it. Senders offer, refusing
 * ([offerRefuse0]..) or evicting ([offerEvict0]..), and hand the connection the queue's feed when [DatagramQueue.wantsFeed]
 * says so. The connection, one thread, takes the evicting queue's oldest and holds it ([takeHold]), lets it go
 * ([releaseHeld]) or takes and lets go at once ([takeRelease]); feeds the refusing queue through a [DatagramSender] whose
 * quiche takes one datagram at a time, so the next waits at the door ([feedOne]); parks a waiter for room ([awaitRoom]);
 * runs a handed feed ([runFeed]); trims both queues' memory ([trim]); and may [close].
 *
 * An audit, not a linearizability specification: an offer is admitted before its datagram is published, so a take
 * between the two finds nothing although the offer is counted, which no sequential queue says. A final [audit] closes
 * both queues and checks what every interleaving must keep: each datagram is taken at most once and only if it was
 * queued, a sender's datagrams leave in its order, no more datagrams are evicted than evicting offers were queued, every
 * queued datagram is taken, sent, evicted or dropped at the close, no queue counts more than its bound or anything once
 * closed, a feed is handed at most once until it runs, every waiter is answered once, and every buffer of slot memory is
 * freed exactly once.
 */
class DatagramQueueLincheckTest {
    private val counts = ConnCounts()
    private val memory = CountingFactory()
    private val refusing = DatagramQueue(BOUND, Overflow.RefuseNew, memory, VirtualClock(), counts, SLACK)
    private val evicting = DatagramQueue(BOUND, Overflow.EvictOldest, memory, VirtualClock(), counts, SLACK)
    private val quiche = OneAtATimeQuiche()
    private val sender =
        DatagramSender(QuicheConnRef(quiche, QuicheConn(1L)), refusing, AgeBound.Unbounded, counts).also { it.readRoom() }

    // Heap buffers, copied through the buffer API: the model checker does not follow raw memory writes.
    private val buffers = Array(SENDERS) { BufferFactory.managed().allocate(4) }
    private val sent = Array(SENDERS) { AtomicInteger() }
    private val queued = AtomicInteger()
    private val evictedBy = AtomicInteger()
    private val violations = AtomicInteger()
    private val handed = Array(2) { AtomicInteger() }
    private val ran = Array(2) { AtomicInteger() }
    private val takenOf = Array(SENDERS) { IntArray(OFFERS + 1) }
    private val lastTaken = IntArray(SENDERS) { -1 }
    private val waiters = ArrayList<AtomicInteger>()
    private var taken = 0
    private var held: Head = Head.Empty
    private var closed = Closed.No

    private enum class Closed { No, Yes }

    @Operation(nonParallelGroup = "sender0")
    fun offerRefuse0() = offer(0, refusing)

    @Operation(nonParallelGroup = "sender1")
    fun offerRefuse1() = offer(1, refusing)

    @Operation(nonParallelGroup = "sender0")
    fun offerEvict0() = offer(0, evicting)

    @Operation(nonParallelGroup = "sender1")
    fun offerEvict1() = offer(1, evicting)

    @Operation(nonParallelGroup = "connection")
    fun takeHold() {
        if (closed == Closed.No && held is Head.Empty) held = takeFrom(evicting)
    }

    @Operation(nonParallelGroup = "connection")
    fun releaseHeld() {
        when (val slot = held) {
            is DatagramSlot -> evicting.done(slot)
            is Head.Empty -> Unit
        }
        held = Head.Empty
    }

    /** Takes and lets go of the evicting queue's oldest; the connection takes nothing once it closed its queues. */
    @Operation(nonParallelGroup = "connection")
    fun takeRelease() {
        if (closed == Closed.Yes) return
        when (val slot = takeFrom(evicting)) {
            is DatagramSlot -> evicting.done(slot)
            is Head.Empty -> Unit
        }
    }

    @Operation(nonParallelGroup = "connection")
    fun feedOne() {
        quiche.room = 1
        sender.quicheWrote()
        sender.feed(NOW)
        checkSize(refusing)
    }

    /** Parks a caller in a [RoomWait], as a send told the queue is full does; counts each time it goes on. */
    @Operation(nonParallelGroup = "connection")
    fun awaitRoom() {
        val answers = AtomicInteger().also { waiters += it }
        suspend { suspendCancellableCoroutine<Unit> { caller -> sender.awaitRoom(RoomWait(caller)) } }
            .startCoroutine(Continuation(EmptyCoroutineContext) { answers.incrementAndGet() })
    }

    @Operation(nonParallelGroup = "connection")
    fun runFeed() {
        listOf(refusing, evicting).forEachIndexed { at, queue ->
            if (handed[at].get() > ran[at].get()) {
                ran[at].incrementAndGet()
                queue.fed()
            }
        }
    }

    @Operation(nonParallelGroup = "connection")
    fun trim() {
        refusing.trim(0)
        evicting.trim(0)
    }

    @Operation(nonParallelGroup = "connection")
    fun close() {
        releaseHeld()
        sender.close(ConnEnd.Released)
        evicting.close(ConnEnd.Released)
        closed = Closed.Yes
    }

    /** After everything: every queued datagram and every buffer is accounted for, and no rule was broken. */
    @Operation(nonParallelGroup = "connection")
    fun audit(): Boolean {
        close()
        if (refusing.size() != 0 || evicting.size() != 0) violations.incrementAndGet()
        if (counts.datagramsEvicted > evictedBy.get()) violations.incrementAndGet()
        val accounted = taken + sender.output + counts.datagramsEvicted + counts.datagramsDroppedAtClose
        if (accounted != queued.get().toLong()) violations.incrementAndGet()
        if (waiters.any { it.get() != 1 }) violations.incrementAndGet()
        if (memory.made.any { it.frees.get() != 1 }) violations.incrementAndGet()
        return violations.get() == 0
    }

    private fun offer(
        sender: Int,
        queue: DatagramQueue,
    ) {
        val n = sent[sender].incrementAndGet()
        val buffer: PlatformBuffer = buffers[sender]
        buffer.position(0)
        buffer.setLimit(4)
        buffer.writeShort(sender.toShort())
        buffer.writeShort(n.toShort())
        buffer.position(0)
        when (queue.offer(buffer, 0, buffer, 4)) {
            is Offered.Queued -> {
                queued.incrementAndGet()
                if (queue === evicting) evictedBy.incrementAndGet()
                val at = if (queue === refusing) 0 else 1
                if (queue.wantsFeed() == Feed.Hand && handed[at].incrementAndGet() - ran[at].get() > 1) violations.incrementAndGet()
            }
            is Offered.Full, is Offered.Contended, is QueueLife.Closed -> Unit
            is Offered.NoMemory -> violations.incrementAndGet()
        }
        checkSize(queue)
    }

    private fun checkSize(queue: DatagramQueue) {
        if (queue.size() !in 0..BOUND) violations.incrementAndGet()
    }

    private fun takeFrom(queue: DatagramQueue): Head {
        val head = queue.take()
        when (head) {
            is DatagramSlot -> {
                val owner = (head.memory as SlotMemory.Made).bytes.owner
                owner.position(0)
                owner.setLimit(4)
                val sender = owner.readShort().toInt()
                val n = owner.readShort().toInt()
                if (takenOf[sender][n]++ > 0 || n <= lastTaken[sender]) violations.incrementAndGet()
                lastTaken[sender] = n
                taken++
            }
            is Head.Empty -> Unit
        }
        return head
    }

    /** A quiche whose datagram queue takes [room] more, each sent at once. */
    private class OneAtATimeQuiche(
        private val inner: StubQuicheApi = StubQuicheApi(),
    ) : QuicheApi by inner {
        var room = 0

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
            return bufLen
        }

        override fun connDgramPurgeOutgoing(conn: QuicheConn): Int = 0
    }

    /** Network buffers that count their frees; only a buffer's first free releases its memory. */
    private class CountingFactory : BufferFactory by BufferFactory.network() {
        val made = ConcurrentLinkedQueue<Counted>()

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = Counted(BufferFactory.network().allocate(size, byteOrder)).also { made.add(it) }

        class Counted(
            private val inner: PlatformBuffer,
        ) : PlatformBuffer by inner {
            val frees = AtomicInteger()

            override fun freeNativeMemory() {
                if (frees.incrementAndGet() == 1) inner.freeNativeMemory()
            }
        }
    }

    @Test
    fun noDatagramIsLostDuplicatedOrEvictedTwiceInAnyInterleaving() {
        ModelCheckingOptions()
            .sequentialSpecification(DatagramQueueSpec::class.java)
            .addCustomScenario {
                parallel {
                    thread {
                        actor(DatagramQueueLincheckTest::offerEvict0)
                        actor(DatagramQueueLincheckTest::offerEvict0)
                        actor(DatagramQueueLincheckTest::offerEvict0)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::offerEvict1)
                        actor(DatagramQueueLincheckTest::offerEvict1)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::takeHold)
                        actor(DatagramQueueLincheckTest::takeRelease)
                        actor(DatagramQueueLincheckTest::releaseHeld)
                    }
                }
                post { actor(DatagramQueueLincheckTest::audit) }
            }.addCustomScenario {
                parallel {
                    thread {
                        actor(DatagramQueueLincheckTest::offerRefuse0)
                        actor(DatagramQueueLincheckTest::offerRefuse0)
                        actor(DatagramQueueLincheckTest::offerRefuse0)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::offerEvict1)
                        actor(DatagramQueueLincheckTest::offerRefuse1)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::takeRelease)
                        actor(DatagramQueueLincheckTest::close)
                    }
                }
                post { actor(DatagramQueueLincheckTest::audit) }
            }.addCustomScenario {
                // A free slot keeps memory; the close comes while an offer is inside, then a trim: the last offer out
                // frees the memory, the trim nothing.
                initial {
                    actor(DatagramQueueLincheckTest::offerEvict0)
                    actor(DatagramQueueLincheckTest::offerEvict1)
                    actor(DatagramQueueLincheckTest::takeRelease)
                    actor(DatagramQueueLincheckTest::takeRelease)
                }
                parallel {
                    thread {
                        actor(DatagramQueueLincheckTest::offerEvict0)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::close)
                        actor(DatagramQueueLincheckTest::trim)
                    }
                }
                post { actor(DatagramQueueLincheckTest::audit) }
            }.addCustomScenario {
                // The close while offers are inside, then a trim, with offers on both sides.
                parallel {
                    thread {
                        actor(DatagramQueueLincheckTest::offerEvict0)
                        actor(DatagramQueueLincheckTest::offerEvict0)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::offerEvict1)
                        actor(DatagramQueueLincheckTest::offerEvict1)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::takeRelease)
                        actor(DatagramQueueLincheckTest::close)
                        actor(DatagramQueueLincheckTest::trim)
                    }
                }
                post { actor(DatagramQueueLincheckTest::audit) }
            }.addCustomScenario {
                // The door, a waiter and the feed hand-off through the sender, beside offers and a trim.
                parallel {
                    thread {
                        actor(DatagramQueueLincheckTest::offerRefuse0)
                        actor(DatagramQueueLincheckTest::offerRefuse0)
                        actor(DatagramQueueLincheckTest::offerRefuse0)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::offerRefuse1)
                        actor(DatagramQueueLincheckTest::offerRefuse1)
                    }
                    thread {
                        actor(DatagramQueueLincheckTest::feedOne)
                        actor(DatagramQueueLincheckTest::awaitRoom)
                        actor(DatagramQueueLincheckTest::runFeed)
                        actor(DatagramQueueLincheckTest::trim)
                        actor(DatagramQueueLincheckTest::feedOne)
                    }
                }
                post { actor(DatagramQueueLincheckTest::audit) }
            }.threads(3)
            .actorsPerThread(5)
            .actorsAfter(1)
            // Only the scenarios above: a generated one could run the audit beside an offer, which it is not for.
            .iterations(0)
            .invocationsPerIteration(LincheckBudget.scaled(3_000))
            .check(this::class)
    }

    private companion object {
        const val BOUND = 2
        const val SLACK = 2
        const val SENDERS = 2
        const val OFFERS = 8
        const val MAX = 1200
        const val DONE = -1
        val NOW = Nanos(0L)
    }
}

/** What every operation promises to a caller: nothing observable but that [audit] holds. */
class DatagramQueueSpec {
    fun offerRefuse0() = Unit

    fun offerRefuse1() = Unit

    fun offerEvict0() = Unit

    fun offerEvict1() = Unit

    fun takeHold() = Unit

    fun releaseHeld() = Unit

    fun takeRelease() = Unit

    fun feedOne() = Unit

    fun awaitRoom() = Unit

    fun runFeed() = Unit

    fun trim() = Unit

    fun close() = Unit

    fun audit(): Boolean = true
}
