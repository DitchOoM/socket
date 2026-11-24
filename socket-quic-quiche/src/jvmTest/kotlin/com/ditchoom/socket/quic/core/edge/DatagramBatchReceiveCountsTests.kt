package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramReceive
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicConnectionStats
import com.ditchoom.socket.quic.QuicDatagramBatch
import com.ditchoom.socket.quic.QuicDatagrams
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Datagrams
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The public datagram receive under a one-way flood of 1000-byte datagrams over loopback, read at full speed by
 * [receiveEach] on a thread of its own: what each delivered datagram costs the reader in bytes allocated and in
 * resumes, and that the batches keep up, so the receive queue drops nothing. Design D5.8 measured the batched shape at
 * 8.5 B and about 0.05 resumes per datagram, with no drops; a receive resumed once per datagram, at 544 B and 92% drops.
 */
class DatagramBatchReceiveCountsTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aFullSpeedReaderAllocatesUnder16BytesAndResumesUnderOncePer4Datagrams() =
        flooded(Reader.Each) { flood ->
            report(Reader.Each, flood)
            assertTrue(
                flood.bytesPer() <= MAX_BYTES_PER_DATAGRAM,
                "reader bytes per datagram: ${flood.bytesPer()}; the bound is $MAX_BYTES_PER_DATAGRAM",
            )
            assertTrue(
                flood.resumesPer() <= MAX_RESUMES_PER_DATAGRAM,
                "resumes per datagram: ${flood.resumesPer()}; the bound is $MAX_RESUMES_PER_DATAGRAM",
            )
        }

    @Test
    fun aFullSpeedReaderLosesNoDatagramToTheReceiveQueue() =
        flooded(Reader.Each) { flood ->
            assertTrue(flood.received > 0, "the flood delivered nothing")
            assertEquals(0L, flood.dropped, "datagrams the receive queue dropped of ${flood.received}; ${flood.evidence()}")
        }

    private class Flood(
        val delivered: Long,
        val callerBytes: Long,
        val resumes: Long,
        val received: Long,
        val dropped: Long,
        val longestGapNanos: Long,
        val pressure: Pressure,
        val gcCount: Long,
        val gcMillis: Long,
    ) {
        /** What a dropping run's failure says: how long the reader waited at most, and how hard the queue was pressed. */
        fun evidence() =
            "longest gap between reader runs ${longestGapNanos / 1_000} us; queue high water ${pressure.highWater}; " +
                "reads that dropped ${pressure.droppingReads}; GC $gcCount collections, $gcMillis ms"

        fun bytesPer() = callerBytes.toDouble() / delivered

        fun resumesPer() = resumes.toDouble() / delivered
    }

    /** Floods a reader, then runs [check] on what it cost. */
    @Test
    fun aReaderOfOneBatchAtATimeAllocatesUnder16BytesAndResumesUnderOncePer4Datagrams() =
        flooded(Reader.Once) { flood ->
            report(Reader.Once, flood)
            assertTrue(
                flood.bytesPer() <= MAX_BYTES_PER_DATAGRAM,
                "reader bytes per datagram: ${flood.bytesPer()}; the bound is $MAX_BYTES_PER_DATAGRAM",
            )
            assertTrue(
                flood.resumesPer() <= MAX_RESUMES_PER_DATAGRAM,
                "resumes per datagram: ${flood.resumesPer()}; the bound is $MAX_RESUMES_PER_DATAGRAM",
            )
        }

    /** How the flood is read: [QuicDatagrams.receiveEach], or [QuicDatagrams.receive] called again after each batch. */
    private enum class Reader { Each, Once }

    private fun report(
        reader: Reader,
        flood: Flood,
    ) = println(
        "[dgram batch] %s | caller %.2f B/op | resumes %.4f/op | drops %d of %d"
            .format(reader, flood.bytesPer(), flood.resumesPer(), flood.dropped, flood.received),
    )

    private suspend fun read(
        reader: Reader,
        datagrams: QuicDatagrams,
        delivered: AtomicLong,
    ): DatagramReceive.NotReceived =
        when (reader) {
            Reader.Each -> datagrams.receiveEach { delivered.incrementAndGet() }
            Reader.Once -> {
                val take: (QuicDatagramBatch) -> Unit = { batch -> delivered.addAndGet(batch.size.toLong()) }
                var why: DatagramReceive.NotReceived = DatagramReceive.NotReceived.ReceiverBusy
                var reading = true
                while (reading) {
                    when (val received = datagrams.receive(take)) {
                        is DatagramReceive.Batch -> Unit
                        is DatagramReceive.NotReceived -> {
                            why = received
                            reading = false
                        }
                    }
                }
                why
            }
        }

    private fun flooded(
        reader: Reader,
        check: (Flood) -> Unit,
    ): Unit =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(DatagramBatchReceiveCountsTests::class) {
                    val accepted = Channel<CoreConnection>(Channel.UNLIMITED)
                    withCoreServer(options = ON, onConnection = { accepted.send(it) }) { server ->
                        val client = connectCore(server.port, loops = loops, factory = BufferFactory.network(), options = ON)
                        val payload = payloadOf(FLOOD_BYTES)
                        val sending = client.connection.coreDatagrams
                        val core = withTimeout(10.seconds) { accepted.receive() }
                        val receiving = core.public()
                        val readerThread = ResumeCountingDispatcher("dgram-batch-reader")
                        val delivered = AtomicLong()
                        val ended = CompletableFuture<Result<DatagramReceive.NotReceived>>()
                        suspend { read(reader, receiving.datagrams, delivered) }
                            .startCoroutine(Continuation(readerThread + Job()) { ended.complete(it) })
                        flood(sending, payload, WARM_UP_MS)
                        settle(delivered)
                        val before = statsOf(core)
                        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                        val readerId = readerThread.threadId()
                        val bytesFrom = threads.getThreadAllocatedBytes(readerId)
                        val resumesFrom = readerThread.dispatches.get()
                        val deliveredFrom = delivered.get()
                        val pressureFrom = pressureOf(core)
                        val gcFrom = gcTotals()
                        readerThread.resetGap()
                        flood(sending, payload, FLOOD_MS)
                        settle(delivered)
                        val after = statsOf(core)
                        val pressureTo = pressureOf(core)
                        val gcTo = gcTotals()
                        val flood =
                            Flood(
                                delivered = delivered.get() - deliveredFrom,
                                callerBytes = threads.getThreadAllocatedBytes(readerId) - bytesFrom,
                                resumes = readerThread.dispatches.get() - resumesFrom,
                                received = after.datagramsReceived - before.datagramsReceived,
                                dropped = after.datagramDrops.receiveQueue - before.datagramDrops.receiveQueue,
                                longestGapNanos = readerThread.longestGap.get(),
                                pressure = Pressure(pressureTo.highWater, pressureTo.droppingReads - pressureFrom.droppingReads),
                                gcCount = gcTo.first - gcFrom.first,
                                gcMillis = gcTo.second - gcFrom.second,
                            )
                        client.connection.close(QuicError.NoError)
                        assertIs<DatagramReceive.NotReceived.Unavailable.ConnectionEnded>(ended.get(10, TimeUnit.SECONDS).getOrThrow())
                        readerThread.close()
                        payload.freeNativeMemory()
                        check(flood)
                    }
                }
            }
        }

    /** Sends [payload] as fast as the send queue takes it for [millis], waiting for room when it is full. */
    private suspend fun flood(
        channel: CoreDatagramChannel,
        payload: PlatformBuffer,
        millis: Long,
    ) {
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) {
            payload.position(0)
            when (channel.send(payload)) {
                is DatagramSend.Sent -> Unit
                is DatagramSend.NotSent.QueueFull -> channel.awaitRoom()
                is DatagramSend.NotSent -> fail("the flood's send was refused")
            }
        }
        yield()
    }

    /** Waits until the reader has delivered all it will: its count holds still for 50 ms. */
    private suspend fun settle(delivered: AtomicLong) {
        var last = -1L
        while (true) {
            delay(50)
            val now = delivered.get()
            if (now == last) return
            last = now
        }
    }

    private class Pressure(
        val highWater: Int,
        val droppingReads: Int,
    )

    /** The server connection's receive-queue pressure, read inside it. */
    private suspend fun pressureOf(connection: CoreConnection): Pressure {
        val conn = CONN_FIELD.get(connection) as Conn
        val read = CompletableDeferred<Pressure>()
        conn.submit(
            object : ConnWork() {
                override fun run(inside: Inside) {
                    when (val datagrams = inside.datagrams) {
                        is Datagrams.On -> read.complete(Pressure(datagrams.box.pressure.highWater, datagrams.box.pressure.droppingReads))
                        is Datagrams.Off -> read.complete(Pressure(0, 0))
                    }
                }

                override fun gone(end: ConnEnd) {
                    read.complete(Pressure(0, 0))
                }
            },
            CallerPlace.HandToLoop,
        )
        return withTimeout(10.seconds) { read.await() }
    }

    /** Collections and milliseconds collecting, over every collector, so far. */
    private fun gcTotals(): Pair<Long, Long> {
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        return collectors.sumOf { maxOf(it.collectionCount, 0L) } to collectors.sumOf { maxOf(it.collectionTime, 0L) }
    }

    private suspend fun statsOf(connection: CoreConnection): QuicConnectionStats = assertIs<QuicStatsRead.Read>(connection.stats()).stats

    private fun payloadOf(size: Int) =
        BufferFactory.network().allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        val CONN_FIELD = CoreConnection::class.java.getDeclaredField("conn").apply { isAccessible = true }
        val ON = coreTestOptions.copy(datagrams = DatagramOptions())
        const val FLOOD_BYTES = 1000
        const val WARM_UP_MS = 500L
        const val FLOOD_MS = 2_500L

        /**
         * D5.8's batched shape measured 8.5 B; the receive measures 4.7 to 5.6 B by [QuicDatagrams.receiveEach] and 8.8 to
         * 10.9 B a batch at a time, JNI and FFM alike. The bound is 8.5 B and nearly as much again.
         */
        const val MAX_BYTES_PER_DATAGRAM = 16.0

        /** D5.8's batched shape measured one resume per 16 to 20 datagrams; the receive measures one per 26 to 33. */
        const val MAX_RESUMES_PER_DATAGRAM = 0.25
    }
}

/** An [ArrayQueueDispatcher] that counts its dispatches: here, each one is a receive the loop resumed. */
private class ResumeCountingDispatcher(
    private val name: String,
) : CoroutineDispatcher() {
    private val inner = ArrayQueueDispatcher(name)
    val dispatches = AtomicLong()

    /** The longest wait, in nanoseconds, between one run of the reader and the next, since [resetGap]. */
    val longestGap = AtomicLong()
    private val lastRun = AtomicLong()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatches.incrementAndGet()
        inner.dispatch(context) {
            val now = System.nanoTime()
            val last = lastRun.getAndSet(now)
            if (last != 0L && now - last > longestGap.get()) longestGap.set(now - last)
            block.run()
        }
    }

    fun resetGap() {
        lastRun.set(0L)
        longestGap.set(0L)
    }

    fun threadId(): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    fun close() = inner.close()
}
