@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.Attribution
import com.ditchoom.socket.quic.core.testing.CountingClock
import com.ditchoom.socket.quic.core.testing.CountingFactory
import com.ditchoom.socket.quic.core.testing.CountingIo
import com.ditchoom.socket.quic.core.testing.PerfGates
import com.ditchoom.socket.quic.core.testing.SeamCount
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The work the core client does per operation on its hot paths, counted exactly where it crosses one of the core's own
 * seams, after warm-up: CPU proxies that an allocation gate does not see. Per operation it counts the network calls
 * (batch sends and receives on the socket's `DatagramBatchIo`, and the loop's waits in its poller), the timer work (turns
 * that re-armed the connection's timer, re-arms that moved its loop deadline, re-arms the loop took, and one-off
 * deadlines made), the clock readings of the loop's `LoopClock`, which the connection reads too, and the buffers taken
 * from and given back to the factory's pool. libquiche's own clock reads and the kernel's system calls are not counted.
 *
 * The counts are averages over many echoes on loopback: how many turns, datagrams and ACKs an echo takes is the
 * scheduler's, so a count is a fraction that repeats to within a few percent, not an integer. Each gate is the value
 * measured plus the margin [ECHO_MARGIN] or [BULK_MARGIN] states. A failed gate prints every count and the
 * callers of the counted calls, sampled over a short extra run. Named `*AllocationTests` so `coreAllocationTest` runs
 * it: on the production QuicheApi, whose clock libquiche can be pinned to the loop's, not on jvmTest's guarded one.
 *
 * Loop and connection counters are plain fields of their thread; the test reads them between echoes, when the client
 * is idle, so a value read a few increments stale is far inside a margin over thousands of echoes.
 */
class CoreWorkCountAllocationTests {
    @Test
    fun anEchoOnOneStreamDoesAFixedAmountOfWork() = runEchoes(Shape.OneStream, ONE_STREAM)

    @Test
    fun anEchoOnANewStreamDoesAFixedAmountOfWork() = runEchoes(Shape.NewStream, NEW_STREAM)

    @Test
    fun aDatagramEchoDoesAFixedAmountOfWork() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(CoreWorkCountAllocationTests::class) {
                    val on = coreTestOptions.copy(datagrams = DatagramOptions())
                    withCoreServer(options = on, onConnection = { echoDatagrams(it.datagramChannel()) }) { server ->
                        withCountedClient(server.port, on) { client, seams ->
                            val channel = client.connection.datagramChannel()
                            val payload = payloadOf(PAYLOAD)
                            onCallerThread {
                                count(
                                    client,
                                    seams,
                                    "datagram echo",
                                    WARM_UP,
                                    MEASURED,
                                    DATAGRAM,
                                ) { datagramEcho(channel, payload) }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun sixteenMebibytesEchoedDoAFixedAmountOfWorkPerMebibyte() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(CoreWorkCountAllocationTests::class) {
                    withEchoServer { port ->
                        withCountedClient(port, coreTestOptions) { client, seams ->
                            val stream = client.connection.openStream()
                            val chunk = BufferFactory.Default.allocate(CHUNK).apply { repeat(CHUNK) { writeByte(it.toByte()) } }
                            // One operation is a mebibyte of a 16 MiB echo: the first transfer warms up, the second is counted, the third attributed.
                            var transfer = 0
                            onCallerThread {
                                count(client, seams, "16 MiB echo, per MiB", BULK_MIB, BULK_MIB, BULK) {
                                    if (transfer++ % BULK_MIB == 0) bulkEcho(stream, chunk)
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun runEchoes(
        shape: Shape,
        gates: Map<Metric, Double>,
    ) = runBlocking {
        withDescriptorsAbove127 {
            skipOnMissingNativeLib(CoreWorkCountAllocationTests::class) {
                CoreQuicAllocationTests().withClosingEchoServer { port ->
                    withCountedClient(port, coreTestOptions) { client, seams ->
                        val payload = payloadOf(PAYLOAD)
                        val shared = client.connection.openStream()
                        onCallerThread {
                            count(client, seams, shape.label, WARM_UP, MEASURED, gates) { shape.echo(client.connection, shared, payload) }
                        }
                    }
                }
            }
        }
    }

    /** The seams of one client: its loop's clock, its socket's sends and receives, and its buffer pool. */
    private class Seams {
        val clock = CountingClock(MonotonicClock)
        val sends = SeamCount()
        val receives = SeamCount()
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                maxPoolSize = POOL_BUFFERS,
                defaultBufferSize = POOL_MIN_BUFFER,
                factory = BufferFactory.Default,
            )
        val factory = CountingFactory(pool)

        fun attribute(on: Attribution) {
            clock.reads.attribution = on
            sends.attribution = on
            receives.attribution = on
            factory.acquires.attribution = on
        }
    }

    /** A client on a loop of its own whose seams are counted, closed with its loop after [body]. */
    private suspend fun withCountedClient(
        port: Int,
        options: QuicOptions,
        body: suspend CoroutineScope.(CoreClient, Seams) -> Unit,
    ) {
        val seams = Seams()
        val loops = LoopSet(1, clock = seams.clock)
        try {
            val client =
                connectCore(
                    port,
                    loops = loops,
                    factory = seams.factory,
                    options = options,
                    wrapIo = { CountingIo(it, seams.sends, seams.receives) },
                )
            coroutineScope { body(client, seams) }
            client.connection.close(QuicError.NoError)
        } finally {
            loops.close()
        }
    }

    /** Every count at one moment: plain loop and connection fields read between operations, and the seams' totals. */
    private class Snapshot(
        client: CoreClient,
        seams: Seams,
    ) {
        private val stats = seams.pool.stats()
        val values: Map<Metric, Long> =
            mapOf(
                Metric.Sends to seams.sends.value(),
                Metric.Receives to seams.receives.value(),
                Metric.Waits to client.loop.counts.waits,
                Metric.TimerRearms to client.conn.counts.timer.rearms,
                Metric.DeadlineMoves to client.conn.counts.timer.moves,
                Metric.RearmsTaken to client.loop.port.rearmsTaken(),
                Metric.DeadlinesMade to client.loop.port.deadlinesMade(),
                Metric.ClockReads to seams.clock.reads.value(),
                Metric.PoolAcquires to seams.factory.acquires.value(),
                Metric.PoolMisses to stats.poolMisses,
                // A release that finds room goes back idle; a hit takes one idle buffer: the releases are the hits plus the idle growth.
                Metric.PoolReleases to stats.poolHits + stats.currentPoolSize,
            )
    }

    /** Runs [operation] [warmUp] then [measured] times on the caller's thread and gates each count per operation. */
    private suspend fun count(
        client: CoreClient,
        seams: Seams,
        name: String,
        warmUp: Int,
        measured: Int,
        gates: Map<Metric, Double>,
        operation: suspend () -> Unit,
    ) {
        repeat(warmUp) { operation() }
        val before = Snapshot(client, seams)
        repeat(measured) { operation() }
        val after = Snapshot(client, seams)
        val perOp = Metric.entries.associateWith { (after.values.getValue(it) - before.values.getValue(it)).toDouble() / measured }
        seams.attribute(Attribution.On)
        repeat(attributed(measured)) { operation() }
        seams.attribute(Attribution.Off)
        val per = attributed(measured)
        val table =
            Metric.entries.joinToString("\n") { m ->
                "  %-15s %8.3f / op (gate %.3f)%s".format(
                    m.label,
                    perOp.getValue(m),
                    gates.getValue(m),
                    if (perOp.getValue(m) >
                        gates.getValue(m)
                    ) {
                        "  OVER"
                    } else {
                        ""
                    },
                )
            }
        val sites =
            "  callers of the clock, over $per more operations:\n${seams.clock.reads.sites(per)}\n" +
                "  callers of a send:\n${seams.sends.sites(per)}\n" +
                "  callers of a receive:\n${seams.receives.sites(per)}\n" +
                "  callers of a pool acquire:\n${seams.factory.acquires.sites(per)}"
        println("[core work counts: $name]\n$table\n$sites")
        val test = name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        Metric.entries.forEach {
            PerfGates.record(CoreWorkCountAllocationTests::class.java, test, it.label, perOp.getValue(it), gates.getValue(it), "per-op")
        }
        val over = Metric.entries.filter { perOp.getValue(it) > gates.getValue(it) }
        assertTrue(over.isEmpty(), "$name: ${over.joinToString { it.label }} over the gate\n$table\n$sites")
    }

    private fun attributed(measured: Int) = maxOf(1, minOf(ATTRIBUTED, measured))

    /** Runs [body] on a caller thread of its own, as the allocation gates do, so in-place entries match theirs. */
    private fun onCallerThread(body: suspend () -> Unit) {
        val dispatcher = ArrayQueueDispatcher(CALLER_THREAD)
        try {
            val done = CompletableFuture<Result<Unit>>()
            body.startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
            done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
        } finally {
            dispatcher.close()
        }
    }

    /** One echo from the caller's side. */
    private sealed interface Shape {
        val label: String

        suspend fun echo(
            connection: CoreConnection,
            shared: QuicByteStream,
            payload: PlatformBuffer,
        )

        data object OneStream : Shape {
            override val label = "suspend echo on one stream"

            override suspend fun echo(
                connection: CoreConnection,
                shared: QuicByteStream,
                payload: PlatformBuffer,
            ) = streamEcho(shared, payload)
        }

        data object NewStream : Shape {
            override val label = "new stream per echo"

            override suspend fun echo(
                connection: CoreConnection,
                shared: QuicByteStream,
                payload: PlatformBuffer,
            ) {
                val stream = connection.openStream()
                streamEcho(stream, payload)
                (stream as HalfCloseable).shutdownSend()
                val end = stream.read(10.seconds)
                if (end !== ReadResult.End) fail("the server must finish its side once ours is finished: $end")
            }
        }
    }

    private enum class Metric(
        val label: String,
    ) {
        Sends("io-sends"),
        Receives("io-receives"),
        Waits("poll-waits"),
        TimerRearms("timer-rearms"),
        DeadlineMoves("deadline-moves"),
        RearmsTaken("rearms-taken"),
        DeadlinesMade("deadlines-made"),
        ClockReads("clock-reads"),
        PoolAcquires("pool-acquires"),
        PoolReleases("pool-releases"),
        PoolMisses("pool-misses"),
    }

    private companion object {
        const val CALLER_THREAD = "core-count-caller"
        const val PAYLOAD = 64
        const val WARM_UP = 2_000
        const val MEASURED = 10_000
        const val ATTRIBUTED = 200
        const val CHUNK = 65_536
        const val BULK_MIB = 16
        const val POOL_BUFFERS = 256
        const val POOL_MIN_BUFFER = 2_048
        const val TIMEOUT_SECONDS = 120L

        /** An echo's gate is what it measured plus a tenth, and never less than one call in twenty operations more. */
        const val ECHO_MARGIN = 0.10
        const val LEAST_MARGIN = 0.05

        /**
         * A bulk transfer's counts follow how the scheduler batches its datagrams: they repeat to within 7%, so a fifth,
         * and at least one event per MiB: a rare event (deadline moves, 18 to 33 per transfer over 15 runs) swings more.
         */
        const val BULK_MARGIN = 0.20
        const val BULK_LEAST_MARGIN = 1.0

        /**
         * The highest of every run measured on macOS arm64 (JDK 21, JNI and FFM, native and JDK stacks), in the order of
         * [Metric]: sends, receives, waits, timer re-arms, deadline moves, re-arms taken, deadlines made, clock reads,
         * pool acquires, releases, misses.
         */
        val ONE_STREAM = gates(ECHO_MARGIN, LEAST_MARGIN, 2.000, 3.946, 3.391, 3.956, 0.004, 2.004, 0.0, 21.805, 1.0, 1.0, 0.0)
        val NEW_STREAM = gates(ECHO_MARGIN, LEAST_MARGIN, 4.021, 7.968, 7.888, 8.976, 0.011, 4.011, 0.0, 47.927, 1.0, 1.0, 0.0)
        val DATAGRAM = gates(ECHO_MARGIN, LEAST_MARGIN, 1.992, 3.809, 2.694, 2.886, 0.003, 0.003, 0.0, 17.453, 1.0, 1.0, 0.0)
        val BULK_MEASURED = doubleArrayOf(1_949.6, 4_009.4, 2_185.4, 3_483.4, 2.063, 3_921.3, 0.0, 20_866.1, 1_151.1, 1_151.1, 0.0)
        val BULK = gates(BULK_MARGIN, BULK_LEAST_MARGIN, *BULK_MEASURED)

        fun gates(
            margin: Double,
            least: Double,
            vararg measured: Double,
        ): Map<Metric, Double> {
            check(measured.size == Metric.entries.size) { "one measured value per metric" }
            return Metric.entries.associateWith { m ->
                val value = measured[m.ordinal]
                maxOf(value * (1 + margin), value + least)
            }
        }
    }
}

private fun payloadOf(size: Int): PlatformBuffer =
    BufferFactory.Default.allocate(size).apply {
        repeat(size) { writeByte(it.toByte()) }
        resetForRead()
    }

/** Writes [payload] on [stream] and reads it back, releasing each result buffer. */
private suspend fun streamEcho(
    stream: QuicByteStream,
    payload: PlatformBuffer,
) {
    payload.position(0)
    stream.write(payload, 10.seconds)
    var received = 0
    while (received < payload.limit()) {
        val result = stream.read(10.seconds)
        if (result !is ReadResult.Data) fail("the echo ended early: $result")
        received += result.buffer.remaining()
        result.buffer.freeIfNeeded()
    }
}

private suspend fun datagramEcho(
    channel: ConnectedDatagramChannel,
    payload: PlatformBuffer,
) {
    payload.position(0)
    channel.send(payload)
    when (val result = channel.receive()) {
        is DatagramReadResult.Received -> result.datagram.payload.freeIfNeeded()
        is DatagramReadResult.Closed -> fail("the echo ended early: ${result.reason}")
    }
}

private suspend fun echoDatagrams(channel: ConnectedDatagramChannel) {
    while (true) {
        when (val result = channel.receive()) {
            is DatagramReadResult.Received -> {
                val payload = result.datagram.payload
                channel.send(payload)
                payload.freeIfNeeded()
            }
            is DatagramReadResult.Closed -> return
        }
    }
}

/** Writes 16 MiB on [stream] in [chunk]s while reading the echo back, releasing each result buffer. */
private suspend fun bulkEcho(
    stream: QuicByteStream,
    chunk: PlatformBuffer,
) = coroutineScope {
    val total = 16 * 1024 * 1024
    launch {
        repeat(total / chunk.capacity) {
            chunk.position(0)
            chunk.setLimit(chunk.capacity)
            stream.writeFully(chunk, 30.seconds)
        }
    }
    var received = 0L
    while (received < total) {
        val result = stream.read(30.seconds)
        if (result !is ReadResult.Data) fail("the echo ended after $received of $total bytes: $result")
        received += result.buffer.remaining()
        result.buffer.freeIfNeeded()
    }
}
