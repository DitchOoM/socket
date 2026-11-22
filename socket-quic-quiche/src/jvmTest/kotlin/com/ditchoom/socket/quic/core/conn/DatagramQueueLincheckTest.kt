package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.LincheckBudget
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.network
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * [DatagramQueue]'s lock-free protocol, model checked as senders and the connection use it. Senders offer, refusing
 * ([offerRefuse0]..) or evicting ([offerEvict0]..); the connection, one thread, takes the oldest and holds it
 * ([takeHold]), lets it go ([releaseHeld]) or takes and lets go at once ([takeRelease]), and may [close].
 *
 * An audit, not a linearizability specification: an offer is admitted before its datagram is published, so a take
 * between the two finds nothing although the offer is counted, which no sequential queue says. A final [audit] checks
 * what every interleaving must keep: each datagram is taken at most once and only if it was queued, a sender's
 * datagrams leave in its order, no more datagrams are evicted than evicting offers were queued, and every queued datagram is taken, evicted,
 * dropped at the close or still queued.
 */
class DatagramQueueLincheckTest {
    private val counts = ConnCounts()
    private val refusing =
        DatagramQueue(BOUND, Overflow.RefuseNew, AgeBound.Unbounded, BufferFactory.network(), VirtualClock(), counts, SLACK)
    private val evicting =
        DatagramQueue(BOUND, Overflow.EvictOldest, AgeBound.Unbounded, BufferFactory.network(), VirtualClock(), counts, SLACK)

    // Heap buffers, copied through the buffer API: the model checker does not follow raw memory writes.
    private val buffers = Array(SENDERS) { BufferFactory.managed().allocate(4) }
    private val sent = Array(SENDERS) { AtomicInteger() }
    private val queued = AtomicInteger()
    private val evictedBy = AtomicInteger()
    private val violations = AtomicInteger()
    private val takenOf = Array(SENDERS) { IntArray(OFFERS + 1) }
    private val lastTaken = Array(2) { IntArray(SENDERS) { -1 } }
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
        if (held is Head.Empty) held = takeFrom(evicting)
    }

    @Operation(nonParallelGroup = "connection")
    fun releaseHeld() {
        when (val slot = held) {
            is DatagramSlot -> evicting.done(slot)
            is Head.Empty -> Unit
        }
        held = Head.Empty
    }

    @Operation(nonParallelGroup = "connection")
    fun takeRelease() {
        listOf(refusing, evicting).forEach { queue ->
            when (val slot = takeFrom(queue)) {
                is DatagramSlot -> queue.done(slot)
                is Head.Empty -> Unit
            }
        }
    }

    @Operation(nonParallelGroup = "connection")
    fun close() {
        releaseHeld()
        refusing.close(ConnEnd.Released)
        evicting.close(ConnEnd.Released)
        closed = Closed.Yes
    }

    /** After everything: every queued datagram is accounted for, and no rule was broken. True when the audit holds. */
    @Operation(nonParallelGroup = "connection")
    fun audit(): Boolean {
        releaseHeld()
        when (closed) {
            Closed.No ->
                listOf(refusing, evicting).forEach { queue ->
                    while (true) {
                        when (val slot = takeFrom(queue)) {
                            is DatagramSlot -> queue.done(slot)
                            is Head.Empty -> break
                        }
                    }
                }
            Closed.Yes -> Unit
        }
        if (counts.datagramsEvicted > evictedBy.get()) violations.incrementAndGet()
        val accounted = taken + counts.datagramsEvicted + counts.datagramsDroppedAtClose
        if (accounted != queued.get().toLong()) violations.incrementAndGet()
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
            is Offered.Queued -> queued.incrementAndGet().also { if (queue === evicting) evictedBy.incrementAndGet() }
            is Offered.Full, is QueueLife.Closed -> Unit
            is Offered.Unaddressable -> violations.incrementAndGet()
        }
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
                val order = lastTaken[if (queue === evicting) 1 else 0]
                if (takenOf[sender][n]++ > 0 || n <= order[sender]) violations.incrementAndGet()
                order[sender] = n
                taken++
            }
            is Head.Empty -> Unit
        }
        return head
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
            }.threads(3)
            .actorsPerThread(3)
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

    fun close() = Unit

    fun audit(): Boolean = true
}
