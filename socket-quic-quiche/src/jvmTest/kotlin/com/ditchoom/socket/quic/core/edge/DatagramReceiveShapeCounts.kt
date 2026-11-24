@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicConnectionStats
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.DATAGRAM_ROOM
import com.ditchoom.socket.quic.core.conn.DatagramWaiter
import com.ditchoom.socket.quic.core.conn.Datagrams
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.Interest
import com.ditchoom.socket.quic.core.conn.Receipt
import com.ditchoom.socket.quic.core.demux.SERVER_LOOP_THREAD
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Design D5.8, measured as counts: what each shape of datagram receive costs per datagram delivered. Reported, not
 * gated. Shapes: (A) the core's `receiveDatagram()`, one suspend per datagram; (B) a batched receive, a test-only op
 * that takes every datagram the connection holds into one caller-owned block of slots and resumes its caller once.
 * Per shape, on one JVM over loopback: the bytes the loop thread and the receiving caller's thread allocate, the
 * caller's dispatcher dispatches (each one a continuation resumed by the loop), and the UDP packets per datagram.
 */
class DatagramReceiveShapeCounts {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /**
     * Where the flood's reader runs: on a thread of its own, resumed by a dispatch, or unconfined, resumed in place on
     * the loop (no dispatch, so its resumes are not counted).
     */
    private enum class Placement { OwnThread, Unconfined }

    /** A way to receive datagrams: answers how many were delivered, 0 once none will come. */
    private sealed interface Shape {
        val label: String

        suspend fun receive(): Int
    }

    private class Suspending(
        private val channel: CoreDatagramChannel,
    ) : Shape {
        override val label = "A suspend receive()"

        override suspend fun receive(): Int =
            when (val received = channel.receiveDatagram()) {
                is DatagramReceive.Received -> {
                    received.buffer.freeIfNeeded()
                    1
                }
                is DatagramReceive.NotReceived -> 0
            }
    }

    private class Batched(
        private val receiver: BatchReceiver,
    ) : Shape {
        override val label = "B batched receive"

        override suspend fun receive(): Int = receiver.receive()
    }

    @Test
    fun pingPongOf64ByteDatagrams() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(DatagramReceiveShapeCounts::class) {
                    withCoreServer(options = ON, onConnection = { echo(it.coreDatagrams) }) { server ->
                        val payload = payloadOf(ECHO_BYTES)
                        for (make in listOf<(CoreConnection) -> Shape>({ Suspending(it.coreDatagrams) }, { Batched(BatchReceiver(it)) })) {
                            val client = connectCore(server.port, loops = loops, factory = BufferFactory.network(), options = ON)
                            val shape = make(client.connection)
                            val dispatcher = CountingDispatcher("dgram-shape-echo")
                            val before = statsOf(client.connection)
                            val row =
                                onDispatcher(dispatcher) {
                                    val channel = client.connection.coreDatagrams

                                    suspend fun once() {
                                        payload.position(0)
                                        assertIs<DatagramSend.Sent>(channel.send(payload))
                                        if (shape.receive() != 1) fail("the echo ended early")
                                    }
                                    repeat(ECHO_WARM_UP) { once() }
                                    val counters = Counters(threadId(client.loop.name), Thread.currentThread().id, dispatcher)
                                    repeat(ECHOES) { once() }
                                    counters.since()
                                }
                            val after = statsOf(client.connection)
                            val wire = (after.packetsSent + after.packetsReceived - before.packetsSent - before.packetsReceived).toDouble()
                            report("echo 64 B", shape.label, row, ECHOES.toLong(), wire / (ECHO_WARM_UP + ECHOES), "n/a")
                            client.connection.close(QuicError.NoError)
                            dispatcher.close()
                        }
                    }
                }
            }
        }

    @Test
    fun oneWayFloodOf1000ByteDatagrams() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(DatagramReceiveShapeCounts::class) {
                    val accepted = Channel<CoreConnection>(Channel.UNLIMITED)
                    withCoreServer(options = ON, onConnection = { accepted.send(it) }) { server ->
                        val payload = payloadOf(FLOOD_BYTES)
                        for ((make, placement) in listOf<Pair<(CoreConnection) -> Shape, Placement>>(
                            { c: CoreConnection -> Suspending(c.coreDatagrams) } to Placement.OwnThread,
                            { c: CoreConnection -> Batched(BatchReceiver(c)) } to Placement.OwnThread,
                            { c: CoreConnection -> Suspending(c.coreDatagrams) } to Placement.Unconfined,
                        )) {
                            val client = connectCore(server.port, loops = loops, factory = BufferFactory.network(), options = ON)
                            val sending = client.connection.coreDatagrams
                            // The first datagram makes the server's connection; the receiver starts once it is handed over.
                            payload.position(0)
                            sending.send(payload)
                            val receiving = withTimeout(10.seconds) { accepted.receive() }
                            val shape = make(receiving)
                            val dispatcher = CountingDispatcher("dgram-shape-flood")
                            val delivered = AtomicLong()
                            val ended = CompletableFuture<kotlin.Result<Unit>>()
                            suspend {
                                while (true) {
                                    val got = shape.receive()
                                    if (got == 0) break
                                    delivered.addAndGet(got.toLong())
                                }
                            }.startCoroutine(
                                Continuation(
                                    when (placement) {
                                        Placement.OwnThread -> dispatcher
                                        Placement.Unconfined -> Dispatchers.Unconfined
                                    } + Job(),
                                ) { ended.complete(it) },
                            )
                            flood(sending, payload, FLOOD_WARM_UP_MS)
                            settle(delivered)
                            val loopId = threadId(SERVER_LOOP_THREAD)
                            // Unconfined, the loop resumes the reader in place: the reader's thread is the loop's.
                            val callerId =
                                when (placement) {
                                    Placement.OwnThread -> dispatcher.threadId()
                                    Placement.Unconfined -> loopId
                                }
                            val before = statsOf(receiving)
                            val counters = Counters(loopId, callerId, dispatcher)
                            val deliveredBefore = delivered.get()
                            val load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
                            flood(sending, payload, FLOOD_MS)
                            settle(delivered)
                            val row = counters.since()
                            val after = statsOf(receiving)
                            val got = delivered.get() - deliveredBefore
                            val received = after.datagramsReceived - before.datagramsReceived
                            val dropped = after.datagramDrops.receiveQueue - before.datagramDrops.receiveQueue
                            val wire = (after.packetsSent + after.packetsReceived - before.packetsSent - before.packetsReceived).toDouble()
                            report(
                                "flood 1000 B",
                                "${shape.label}, $placement",
                                row,
                                got,
                                wire / got,
                                "%.4f (%d of %d, load1 %.2f, %.0f delivered/s) | %s".format(
                                    dropped.toDouble() / received,
                                    dropped,
                                    received,
                                    load,
                                    got * 1000.0 / FLOOD_MS,
                                    row.cpu(got),
                                ),
                            )
                            client.connection.close(QuicError.NoError)
                            ended.get(10, TimeUnit.SECONDS)
                            dispatcher.close()
                            assertTrue(got > 0, "${shape.label}: nothing was delivered")
                        }
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

    /** Waits until the receiver has delivered all it will: its count holds still for 50 ms. */
    private suspend fun settle(delivered: AtomicLong) {
        var last = -1L
        while (true) {
            delay(50)
            val now = delivered.get()
            if (now == last) return
            last = now
        }
    }

    private class Row(
        val loop: Long,
        val caller: Long,
        val dispatches: Long,
        val loopCpu: Long,
        val callerCpu: Long,
        val wall: Long,
    ) {
        /** The CPU each thread spent per [datagrams], in ns, and its share of the wall time. */
        fun cpu(datagrams: Long) =
            "loop cpu %.0f ns/op (%.2f of wall), reader cpu %.0f ns/op (%.2f of wall)".format(
                loopCpu.toDouble() / datagrams,
                loopCpu.toDouble() / wall,
                callerCpu.toDouble() / datagrams,
                callerCpu.toDouble() / wall,
            )
    }

    /** The two threads' allocation and CPU counters and the caller's dispatches, from when it is made. */
    private class Counters(
        private val loop: Long,
        private val caller: Long,
        private val dispatches: () -> Long,
    ) {
        constructor(loop: Long, caller: Long, dispatcher: CountingDispatcher) : this(loop, caller, { dispatcher.dispatches.get() })

        private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private val loopFrom = threads.getThreadAllocatedBytes(loop)
        private val callerFrom = threads.getThreadAllocatedBytes(caller)
        private val dispatchesFrom = dispatches()
        private val loopCpuFrom = threads.getThreadCpuTime(loop)
        private val callerCpuFrom = threads.getThreadCpuTime(caller)
        private val wallFrom = System.nanoTime()

        fun since() =
            Row(
                threads.getThreadAllocatedBytes(loop) - loopFrom,
                threads.getThreadAllocatedBytes(caller) - callerFrom,
                dispatches() - dispatchesFrom,
                threads.getThreadCpuTime(loop) - loopCpuFrom,
                threads.getThreadCpuTime(caller) - callerCpuFrom,
                System.nanoTime() - wallFrom,
            )
    }

    private fun report(
        workload: String,
        shape: String,
        row: Row,
        datagrams: Long,
        wirePer: Double,
        drops: String,
    ) = println(
        "[dgram shape] %s | %s | loop %.1f B/op | caller %.1f B/op | resumes %.3f/op | dispatches %.3f/op | wire %.3f/op | drops %s | n=%d"
            .format(
                workload,
                shape,
                row.loop.toDouble() / datagrams,
                row.caller.toDouble() / datagrams,
                row.dispatches.toDouble() / datagrams,
                row.dispatches.toDouble() / datagrams,
                wirePer,
                drops,
                datagrams,
            ),
    )

    private suspend fun echo(channel: CoreDatagramChannel) {
        while (true) {
            when (val received = channel.receiveDatagram()) {
                is DatagramReceive.Received -> {
                    channel.send(received.buffer)
                    received.buffer.freeIfNeeded()
                }
                is DatagramReceive.NotReceived -> return
            }
        }
    }

    private fun <T> onDispatcher(
        dispatcher: CoroutineDispatcher,
        block: suspend () -> T,
    ): T {
        val done = CompletableFuture<kotlin.Result<T>>()
        block.startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
        return done.get(120, TimeUnit.SECONDS).getOrThrow()
    }

    private suspend fun statsOf(connection: CoreConnection): QuicConnectionStats = assertIs<QuicStatsRead.Read>(connection.stats()).stats

    private fun payloadOf(size: Int) =
        BufferFactory.network().allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    private companion object {
        val ON = coreTestOptions.copy(datagrams = DatagramOptions())
        const val ECHO_BYTES = 64
        const val FLOOD_BYTES = 1000
        const val ECHO_WARM_UP = 2_000
        const val ECHOES = 5_000
        const val FLOOD_WARM_UP_MS = 500L
        const val FLOOD_MS = 2_500L
    }
}

/** An [ArrayQueueDispatcher] that counts its dispatches: here, each one is a continuation the loop resumed. */
internal class CountingDispatcher(
    private val name: String,
) : CoroutineDispatcher() {
    private val inner = ArrayQueueDispatcher(name)
    val dispatches = AtomicLong()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatches.incrementAndGet()
        inner.dispatch(context, block)
    }

    fun threadId(): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    fun close() = inner.close()
}

/**
 * Shape (B), test-only: one receive takes every datagram the connection holds, up to [SLOTS], into one block of native
 * memory the receiver owns, and resumes its caller once. With none held it parks one slot and answers 1 when that
 * slot is filled. Cancellation is not handled: a harness, not an API.
 */
private class BatchReceiver(
    connection: CoreConnection,
) {
    private val conn = CONN_FIELD.get(connection) as Conn
    private val memory = BufferFactory.network().allocate(SLOTS * DATAGRAM_ROOM)
    private val base =
        when (val at = NativeBytes.addressOf(memory)) {
            is ReadAddress.At -> at.address
            is ReadAddress.NotAddressable -> error("the batch's memory must be native")
        }
    val lengths = IntArray(SLOTS)
    private val slots = Array(SLOTS) { Slot(it) }
    private var caller: CancellableContinuation<Int>? = null
    private var parked = Parked.No

    suspend fun receive(): Int =
        suspendCancellableCoroutine { continuation ->
            caller = continuation
            conn.submit(Take(), CallerPlace.HandToLoop)
        }

    private enum class Parked { No, Yes }

    private fun answer(count: Int) {
        parked = Parked.No
        val waiting = caller!!
        caller = null
        waiting.resume(count)
    }

    private inner class Slot(
        index: Int,
    ) : DatagramWaiter {
        private val at = index
        override val address: Long = base + index.toLong() * DATAGRAM_ROOM
        override val capacity: Int = DATAGRAM_ROOM

        override fun interest() = Interest.Wanted

        override fun arrived(length: Int) {
            lengths[at] = length
            when (parked) {
                Parked.Yes -> answer(1)
                Parked.No -> Unit
            }
        }

        override fun refused(code: Int) = answer(0)

        override fun discard() = Unit

        override fun connectionEnded(end: ConnEnd) = answer(0)
    }

    /** Inside the connection: fills slots from what it holds; parks the first only when it holds none. */
    private inner class Take : ConnWork() {
        override fun run(inside: Inside) {
            val box =
                when (val datagrams = inside.datagrams) {
                    is Datagrams.Off -> return answer(0)
                    is Datagrams.On -> datagrams.box
                }
            val now = inside.now()
            var taken = 0
            while (taken < SLOTS) {
                when (box.receive(slots[taken], now)) {
                    Receipt.Delivered -> taken++
                    Receipt.Parked -> {
                        if (taken == 0) {
                            parked = Parked.Yes
                            return
                        }
                        box.withdraw(slots[taken])
                        return answer(taken)
                    }
                }
            }
            answer(taken)
        }

        override fun gone(end: ConnEnd) = answer(0)
    }

    private companion object {
        const val SLOTS = 64
        val CONN_FIELD = CoreConnection::class.java.getDeclaredField("conn").apply { isAccessible = true }
    }
}
