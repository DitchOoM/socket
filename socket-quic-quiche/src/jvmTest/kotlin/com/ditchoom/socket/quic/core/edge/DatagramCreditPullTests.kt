@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramReceive
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicConnectionStats
import com.ditchoom.socket.quic.QuicDatagramBatch
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.conn.BatchWaiter
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.DatagramBox
import com.ditchoom.socket.quic.core.conn.Datagrams
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.LEND_WINDOW
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.forEach
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A batch reader of received datagrams against quiche's receive queue on a virtual link: a reader that holds its batch
 * while datagrams arrive is a stall of exactly that many datagrams. Within the queue's length it loses none; past it, it
 * loses exactly the excess, oldest first, every one counted, at a statistics read and at the connection's end; a parked
 * reader is woken by the first arrival; and a connection holds at most its lend window of datagram slots.
 */
class DatagramCreditPullTests {
    private fun options(recvQueueLen: Int) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            datagrams = DatagramOptions(recvQueueLen = recvQueueLen, sendQueueLen = SEND_QUEUE),
        )

    @Test
    fun aReaderHoldingItsBatchLosesNothingWhileArrivalsStayWithinTheQueue() =
        VirtualPair.with(options(QUEUE)) { pair ->
            val receiver = pair.accept()
            takeFirstBatch(pair, receiver)
            send(pair, 1..QUEUE)
            assertEquals(0L, statsOf(pair, receiver).datagramDrops.receiveQueue, "a stall within the queue drops none")
            assertEquals((1..QUEUE).toList(), readAll(pair, receiver), "every one is delivered, in order")
        }

    @Test
    fun aReaderHoldingItsBatchLosesExactlyTheExcessOverTheQueueOldestFirst() =
        VirtualPair.with(options(QUEUE)) { pair ->
            val receiver = pair.accept()
            takeFirstBatch(pair, receiver)
            send(pair, 1..FLOOD)
            val stats = statsOf(pair, receiver)
            assertEquals((FLOOD - QUEUE).toLong(), stats.datagramDrops.receiveQueue, "the excess over the queue, counted: $stats")
            assertEquals((FLOOD - QUEUE + 1..FLOOD).toList(), readAll(pair, receiver), "the newest are kept, in order")
            assertEquals(stats.datagramsReceived, 1 + FLOOD.toLong(), "the link lost none")
        }

    @Test
    fun aStoppedReaderKeepsTheNewestFourAndCountsTheRestAtTheConnectionsEnd() =
        VirtualPair.with(options(4)) { pair ->
            val receiver = pair.accept()
            takeFirstBatch(pair, receiver)
            send(pair, 1..SMALL_FLOOD)
            assertEquals((SMALL_FLOOD - 3..SMALL_FLOOD).toList(), readAll(pair, receiver), "the newest four, in order")
            send(pair, 1..SMALL_FLOOD)
            receiver.closeNow(QuicError.NoError)
            pair.runUntil { receiver.end() is EdgeEnd.Ended }
            assertEquals(
                2L * (SMALL_FLOOD - 4),
                receiver.counts.datagramsOverflowed,
                "every drop is counted once the connection ended, without a statistics read",
            )
        }

    @Test
    fun dropsAreCountedWhereTheReaderStood() =
        VirtualPair.with(options(4)) { pair ->
            val receiver = pair.accept()
            takeFirstBatch(pair, receiver)
            send(pair, 1..SMALL_FLOOD)
            assertEquals(SMALL_FLOOD - 4L, statsOf(pair, receiver).datagramDrops.receiveQueue)
            assertEquals(
                Drops(away = SMALL_FLOOD - 4L, parked = 0L, told = 0L),
                dropsBy(pair, receiver),
                "a reader away with its batch fell behind",
            )
            readAll(pair, receiver)
            val datagrams = receiver.public().datagrams
            val parked = async(start = CoroutineStart.UNDISPATCHED) { datagrams.receive { batch -> firstBytes(batch) } }
            pair.runFor(pair.oneWay)
            send(pair, 1..SMALL_FLOOD)
            assertEquals((SMALL_FLOOD - 3..SMALL_FLOOD).toList(), assertIs<DatagramReceive.Batch<List<Int>>>(parked.await()).value)
            assertEquals(
                Drops(away = SMALL_FLOOD - 4L, parked = 0L, told = SMALL_FLOOD - 4L),
                dropsBy(pair, receiver),
                "the reads after the one that told the parked reader outran the queue before it was lent",
            )
        }

    @Test
    fun dropsWhileAToldReaderHasNotLentAgainAreTheHostsNotTheBoxs() =
        VirtualPair.with(options(4)) { pair ->
            val receiver = pair.accept()
            val reader = ToldReader()
            lendTo(pair, receiver, reader)
            send(pair, 1..1)
            assertEquals(1, reader.told, "the first arrival told the parked reader")
            send(pair, 2..SMALL_FLOOD)
            assertEquals(SMALL_FLOOD - 4L, statsOf(pair, receiver).datagramDrops.receiveQueue)
            assertEquals(
                Drops(away = 0L, parked = 0L, told = SMALL_FLOOD - 4L),
                dropsBy(pair, receiver),
                "the reader was told and did not lend again: none of the drops is the box's",
            )
        }

    @Test
    fun aParkedReaderIsWokenByTheFirstArrival() =
        VirtualPair.with(options(QUEUE)) { pair ->
            val receiver = pair.accept()
            val datagrams = receiver.public().datagrams
            val got = async(start = CoroutineStart.UNDISPATCHED) { datagrams.receive { batch -> firstBytes(batch) } }
            pair.runFor(pair.oneWay * 4)
            assertFalse(got.isCompleted, "nothing was sent: the reader stays parked")
            queue(pair, 1..1)
            pair.runUntil(within = pair.oneWay * 2) { got.isCompleted }
            assertEquals(listOf(1), assertIs<DatagramReceive.Batch<List<Int>>>(got.await()).value, "the first arrival woke it")
        }

    @Test
    fun aConnectionHoldsAtMostItsLendWindowOfSlotsAfterAFlood() =
        VirtualPair.with(options(BIG_QUEUE)) { pair ->
            val receiver = pair.accept()
            takeFirstBatch(pair, receiver)
            send(pair, 1..BIG_QUEUE)
            assertEquals(BIG_QUEUE, readAll(pair, receiver).size, "the flood fit the queue")
            val slots = slotsOf(pair, receiver)
            println("[dgram credit] slots held after a flood of $BIG_QUEUE: $slots")
            assertTrue(slots <= LEND_WINDOW, "slots held after the flood: $slots; the lend window is $LEND_WINDOW")
        }

    /** A reader takes the first datagram's batch, then holds it: it receives again only when the test reads on. */
    private suspend fun CoroutineScope.takeFirstBatch(
        pair: VirtualPair,
        receiver: CoreConnection,
    ) {
        val datagrams = receiver.public().datagrams
        val first = async(start = CoroutineStart.UNDISPATCHED) { datagrams.receive { batch -> batch.size } }
        send(pair, 0..0)
        pair.runUntil { first.isCompleted }
        assertEquals(1, assertIs<DatagramReceive.Batch<Int>>(first.await()).value, "the first batch")
    }

    /** Every datagram [receiver] holds, read a batch at a time until a receive finds none within a round trip. */
    private suspend fun CoroutineScope.readAll(
        pair: VirtualPair,
        receiver: CoreConnection,
    ): List<Int> {
        val datagrams = receiver.public().datagrams
        val read = ArrayList<Int>()
        while (true) {
            val got = async(start = CoroutineStart.UNDISPATCHED) { datagrams.receive { batch -> firstBytes(batch) } }
            pair.runFor(pair.oneWay * 2)
            if (!got.isCompleted) {
                got.cancel()
                pair.runFor(pair.oneWay)
                return read
            }
            read += assertIs<DatagramReceive.Batch<List<Int>>>(got.await()).value
        }
    }

    private fun firstBytes(batch: QuicDatagramBatch): List<Int> {
        val out = ArrayList<Int>(batch.size)
        batch.forEach { out += it.readShort().toInt() }
        return out
    }

    /** The client sends one datagram numbered by each of [numbers], all queued before its loop runs, and the link carries them. */
    private suspend fun send(
        pair: VirtualPair,
        numbers: IntRange,
    ) {
        queue(pair, numbers)
        pair.runFor(pair.oneWay * 3)
    }

    /** The client queues one datagram numbered by each of [numbers]; none leaves before the pair runs. */
    private fun queue(
        pair: VirtualPair,
        numbers: IntRange,
    ) {
        val channel = pair.client.connection.coreDatagrams
        for (n in numbers) {
            val payload = payloadOf(n)
            assertIs<DatagramSend.Sent>(channel.send(payload), "send $n")
            payload.freeIfNeeded()
        }
    }

    private fun payloadOf(n: Int): PlatformBuffer =
        BufferFactory.network().allocate(PAYLOAD).apply {
            writeShort(n.toShort())
            repeat(PAYLOAD - 2) { writeByte(0) }
            resetForRead()
        }

    private suspend fun CoroutineScope.statsOf(
        pair: VirtualPair,
        connection: CoreConnection,
    ): QuicConnectionStats {
        val read = async(start = CoroutineStart.UNDISPATCHED) { connection.stats() }
        pair.runUntil { read.isCompleted }
        return assertIs<QuicStatsRead.Read>(read.await()).stats
    }

    /** The datagram slots [connection]'s receive side holds, read inside it. */
    private suspend fun slotsOf(
        pair: VirtualPair,
        connection: CoreConnection,
    ): Int = inside(pair, connection, -1) { it.slotsHeld() }

    /** Drops counted by where the batch reader stood. */
    private data class Drops(
        val away: Long,
        val parked: Long,
        val told: Long,
    )

    /** Drops counted by where the batch reader stood, read inside [connection]. */
    private suspend fun dropsBy(
        pair: VirtualPair,
        connection: CoreConnection,
    ): Drops =
        inside(pair, connection, Drops(-1L, -1L, -1L)) {
            Drops(it.pressure.dropsWhileAway, it.pressure.dropsWhileParked, it.pressure.dropsWhileTold)
        }

    /** [reader] is lent what [connection]'s box holds, inside it: with none held, it parks there. */
    private suspend fun lendTo(
        pair: VirtualPair,
        connection: CoreConnection,
        reader: BatchWaiter,
    ) {
        val conn = CONN_FIELD.get(connection) as Conn
        val lent = CompletableDeferred<Unit>()
        conn.submit(
            object : ConnWork() {
                override fun run(inside: Inside) {
                    when (val datagrams = inside.datagrams) {
                        is Datagrams.On -> datagrams.box.lend(reader, inside.now())
                        is Datagrams.Off -> Unit
                    }
                    lent.complete(Unit)
                }

                override fun gone(end: ConnEnd) {
                    lent.complete(Unit)
                }
            },
            CallerPlace.HandToLoop,
        )
        pair.runUntil { lent.isCompleted }
    }

    /** A batch reader that is told datagrams arrived and never lends again, as one whose thread the host does not run. */
    private class ToldReader : BatchWaiter {
        var told = 0

        override fun lent(
            buffer: PlatformBuffer,
            length: Int,
        ) = Unit

        override fun arrived() {
            told++
        }

        override fun connectionEnded(end: ConnEnd) = Unit
    }

    /** What [read] answers of [connection]'s datagram box, inside it; [gone] once it ended. */
    private suspend fun <T> inside(
        pair: VirtualPair,
        connection: CoreConnection,
        gone: T,
        read: (DatagramBox) -> T,
    ): T {
        val conn = CONN_FIELD.get(connection) as Conn
        val answer = CompletableDeferred<T>()
        conn.submit(
            object : ConnWork() {
                override fun run(inside: Inside) {
                    when (val datagrams = inside.datagrams) {
                        is Datagrams.On -> answer.complete(read(datagrams.box))
                        is Datagrams.Off -> answer.complete(gone)
                    }
                }

                override fun gone(end: ConnEnd) {
                    answer.complete(gone)
                }
            },
            CallerPlace.HandToLoop,
        )
        pair.runUntil { answer.isCompleted }
        return answer.await()
    }

    private companion object {
        val CONN_FIELD = CoreConnection::class.java.getDeclaredField("conn").apply { isAccessible = true }

        /** Small datagrams, so a packet carries many and quiche's queue can fill inside one receive. */
        const val PAYLOAD = 16
        const val QUEUE = 32
        const val FLOOD = 100
        const val SMALL_FLOOD = 20
        const val BIG_QUEUE = 600
        const val SEND_QUEUE = 2_048
    }
}
