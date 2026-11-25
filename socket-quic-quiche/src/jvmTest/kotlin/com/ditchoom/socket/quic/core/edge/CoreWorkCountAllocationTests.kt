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
import com.ditchoom.socket.quic.core.sched.SendRule
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
 * seams, after warm-up: CPU proxies that an allocation gate does not see. It counts the network calls (batch sends and
 * receives on the socket's `DatagramBatchIo`, and the loop's waits in its poller), the loop's passes and the
 * connection's serves, the timer work (turns that re-armed the connection's timer, re-arms that moved its loop
 * deadline, re-arms the loop took, and one-off deadlines made), the clock readings of the loop's `LoopClock`, which the
 * connection reads too, and the buffers taken from and given back to the factory's pool. libquiche's own clock reads
 * and the kernel's system calls are not counted.
 *
 * The counts are averages over many echoes on loopback. An echo's events are fixed (its datagrams in and out, the work
 * a caller hands the loop), but how many loop passes and connection serves they spread over is the scheduler's: events
 * that land within one pass share it, and a loaded host shares fewer. So a count that each pass makes (socket reads,
 * waits, clock readings) is gated per [Per.Pass], the timer's re-arms per [Per.Serve], and the passes and serves
 * themselves per operation only where an echo's events never share one (see [SHARED_TURNS]). Each operation runs under
 * both [SendRule]s, whichever this host defaults to, and each gate is the highest value measured under that rule plus the
 * margin [ECHO_MARGIN] states; a count no margin holds is [Gate.TrendOnly] (see [BULK_EACH]). A failed gate prints every count and the
 * callers of the counted calls, sampled over a short extra run. Named `*AllocationTests` so `coreAllocationTest` runs
 * it: on the production QuicheApi, whose clock libquiche can be pinned to the loop's, not on jvmTest's guarded one.
 *
 * Loop and connection counters are plain fields of their thread; the test reads them between echoes, when the client
 * is idle, so a value read a few increments stale is far inside a margin over thousands of echoes.
 */
class CoreWorkCountAllocationTests {
    @Test
    fun anEchoOnOneStreamDoesAFixedAmountOfWorkWhenTheLoopSends() = runEchoes(Shape.OneStream, SendRule.ByLoop)

    @Test
    fun anEchoOnOneStreamDoesAFixedAmountOfWorkWhenCallersSendInPlace() = runEchoes(Shape.OneStream, SendRule.InPlace)

    @Test
    fun anEchoOnANewStreamDoesAFixedAmountOfWorkWhenTheLoopSends() = runEchoes(Shape.NewStream, SendRule.ByLoop)

    @Test
    fun anEchoOnANewStreamDoesAFixedAmountOfWorkWhenCallersSendInPlace() = runEchoes(Shape.NewStream, SendRule.InPlace)

    @Test
    fun aDatagramEchoDoesAFixedAmountOfWorkWhenTheLoopSends() = datagramEchoes(SendRule.ByLoop)

    @Test
    fun aDatagramEchoDoesAFixedAmountOfWorkWhenCallersSendInPlace() = datagramEchoes(SendRule.InPlace)

    @Test
    fun sixteenMebibytesEchoedDoAFixedAmountOfWorkPerMebibyteWhenTheLoopSends() = bulkEchoes(SendRule.ByLoop)

    @Test
    fun sixteenMebibytesEchoedDoAFixedAmountOfWorkPerMebibyteWhenCallersSendInPlace() = bulkEchoes(SendRule.InPlace)

    private fun datagramEchoes(rule: SendRule) =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(CoreWorkCountAllocationTests::class) {
                    val on = coreTestOptions.copy(datagrams = DatagramOptions())
                    withCoreServer(options = on, onConnection = { echoDatagrams(it.datagramChannel()) }) { server ->
                        withCountedClient(server.port, on, rule) { client, seams ->
                            val channel = client.connection.datagramChannel()
                            val payload = payloadOf(PAYLOAD)
                            onCallerThread {
                                count(client, seams, "datagram echo", rule, WARM_UP, MEASURED, DATAGRAM) { datagramEcho(channel, payload) }
                            }
                        }
                    }
                }
            }
        }

    private fun bulkEchoes(rule: SendRule) =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(CoreWorkCountAllocationTests::class) {
                    withEchoServer { port ->
                        withCountedClient(port, coreTestOptions, rule) { client, seams ->
                            val stream = client.connection.openStream()
                            val chunk = BufferFactory.Default.allocate(CHUNK).apply { repeat(CHUNK) { writeByte(it.toByte()) } }
                            // One operation is a mebibyte of a 16 MiB echo: the first transfer warms up, the second is counted, the third attributed.
                            var transfer = 0
                            onCallerThread {
                                count(client, seams, "16 MiB echo, per MiB", rule, BULK_MIB, BULK_MIB, BULK) {
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
        rule: SendRule,
    ) = runBlocking {
        withDescriptorsAbove127 {
            skipOnMissingNativeLib(CoreWorkCountAllocationTests::class) {
                CoreQuicAllocationTests().withClosingEchoServer { port ->
                    withCountedClient(port, coreTestOptions, rule) { client, seams ->
                        val payload = payloadOf(PAYLOAD)
                        val shared = client.connection.openStream()
                        onCallerThread {
                            count(client, seams, shape.label, rule, WARM_UP, MEASURED, shape.gates) {
                                shape.echo(client.connection, shared, payload)
                            }
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
        rule: SendRule,
        body: suspend CoroutineScope.(CoreClient, Seams) -> Unit,
    ) {
        val seams = Seams()
        val loops = LoopSet(1, clock = seams.clock, sends = rule)
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
                Metric.Passes to client.loop.counts.passes,
                Metric.Serves to client.conn.counts.timer.serves,
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
        operation: String,
        rule: SendRule,
        warmUp: Int,
        measured: Int,
        gatesByRule: Gates,
        once: suspend () -> Unit,
    ) {
        val name =
            when (rule) {
                is SendRule.ByLoop -> "$operation, the loop sends"
                is SendRule.InPlace -> "$operation, callers send in place"
            }
        val gates = gatesByRule.of(rule)
        repeat(warmUp) { once() }
        val before = Snapshot(client, seams)
        repeat(measured) { once() }
        val after = Snapshot(client, seams)
        val delta = Metric.entries.associateWith { after.values.getValue(it) - before.values.getValue(it) }
        val perOp =
            Metric.entries.associateWith { m ->
                val over =
                    when (m.per) {
                        Per.Op -> measured.toLong()
                        Per.Pass -> delta.getValue(Metric.Passes)
                        Per.Serve -> delta.getValue(Metric.Serves)
                    }
                delta.getValue(m).toDouble() / maxOf(1L, over)
            }
        seams.attribute(Attribution.On)
        repeat(attributed(measured)) { once() }
        seams.attribute(Attribution.Off)
        val per = attributed(measured)
        val table =
            Metric.entries.joinToString("\n") { m ->
                "  %-15s %8.3f / %-5s (%s)%s".format(
                    m.label,
                    perOp.getValue(m),
                    m.per.unit,
                    gates.getValue(m),
                    over(gates.getValue(m), perOp.getValue(m)),
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
            PerfGates.record(
                CoreWorkCountAllocationTests::class.java,
                test,
                it.label,
                perOp.getValue(it),
                gates.getValue(it).limit(),
                "per-${it.per.unit}",
            )
        }
        val over = Metric.entries.filter { over(gates.getValue(it), perOp.getValue(it)).isNotEmpty() }
        assertTrue(over.isEmpty(), "$name: ${over.joinToString { it.label }} over the gate\n$table\n$sites")
    }

    private fun over(
        gate: Gate,
        value: Double,
    ): String =
        when (gate) {
            is Gate.AtMost -> if (value > gate.limit) "  OVER" else ""
            is Gate.TrendOnly -> ""
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
        val gates: Gates

        suspend fun echo(
            connection: CoreConnection,
            shared: QuicByteStream,
            payload: PlatformBuffer,
        )

        data object OneStream : Shape {
            override val label = "suspend echo on one stream"
            override val gates = ONE_STREAM

            override suspend fun echo(
                connection: CoreConnection,
                shared: QuicByteStream,
                payload: PlatformBuffer,
            ) = streamEcho(shared, payload)
        }

        data object NewStream : Shape {
            override val label = "new stream per echo"
            override val gates = NEW_STREAM

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

    /** What a count is held to. */
    private sealed interface Gate {
        /** The measured value plus its margin. */
        class AtMost(
            val limit: Double,
        ) : Gate {
            override fun toString() = "gate %.3f".format(limit)
        }

        /** A count whose spread between runs is wider than any margin: recorded for the trend, not gated. */
        data object TrendOnly : Gate {
            override fun toString() = "trend only"
        }

        fun limit(): Double =
            when (this) {
                is AtMost -> limit
                is TrendOnly -> Double.NaN
            }
    }

    /** The gates of one operation under each [SendRule]: a caller that sends in place does part of the loop's work itself. */
    private class Gates(
        private val byLoop: Map<Metric, Gate>,
        private val inPlace: Map<Metric, Gate>,
    ) {
        fun of(rule: SendRule): Map<Metric, Gate> =
            when (rule) {
                is SendRule.ByLoop -> byLoop
                is SendRule.InPlace -> inPlace
            }
    }

    /** What a count is divided by: the operations, the loop's passes in them, or the connection's serves in them. */
    private enum class Per(
        val unit: String,
    ) {
        Op("op"),
        Pass("pass"),
        Serve("serve"),
    }

    private enum class Metric(
        val label: String,
        val per: Per,
    ) {
        Sends("io-sends", Per.Op),
        Receives("io-receives", Per.Pass),
        Passes("loop-passes", Per.Op),
        Serves("conn-serves", Per.Op),

        /** At most one a pass: how many passes find their input already waiting is the scheduler's. */
        Waits("poll-waits", Per.Pass),

        /** At most one a serve: how many serves an echo's events spread over is the scheduler's. */
        TimerRearms("timer-rearms", Per.Serve),
        DeadlineMoves("deadline-moves", Per.Op),
        RearmsTaken("rearms-taken", Per.Op),
        DeadlinesMade("deadlines-made", Per.Op),
        ClockReads("clock-reads", Per.Pass),
        PoolAcquires("pool-acquires", Per.Op),
        PoolReleases("pool-releases", Per.Op),
        PoolMisses("pool-misses", Per.Op),
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

        /** Each pass and serve of an echo is its own: the passes and serves per operation are gated. */
        val NONE_TREND = emptySet<Metric>()

        /** A datagram echo's two arrivals share a pass, or do not, as the host's load has it: 2.6 to 3.3 serves an echo. */
        val SHARED_TURNS = setOf(Metric.Passes, Metric.Serves)

        /**
         * The highest of every run measured, in the order of [Metric]: sends, receives a pass, passes, serves, waits a pass,
         * timer re-arms a serve, deadline moves, re-arms taken, deadlines made, clock reads a pass, pool acquires, releases,
         * misses. The per-op counts: macOS arm64, JDK 21, JNI and FFM, native and JDK stacks, and macOS CI, the loop sending;
         * those, Linux x64 CI (JDK 17, 21, 25; JNI, FFM) and Windows CI, callers in place. The per-pass and per-serve counts,
         * and the passes and serves: macOS arm64, JDK 21, JNI and FFM, idle and with every core busy; the datagram echo's
         * receives and clock reads a pass, Windows CI too, where its two arrivals share more passes.
         */
        val ONE_STREAM =
            Gates(
                gates(NONE_TREND, 2.000, 0.999, 3.968, 4.959, 0.977, 0.798, 0.018, 2.005, 0.0, 5.547, 1.0, 1.0, 0.0),
                gates(NONE_TREND, 2.000, 2.070, 1.997, 4.996, 1.000, 0.800, 0.021, 2.005, 0.0, 9.429, 1.0, 1.0, 0.0),
            )
        val NEW_STREAM =
            Gates(
                gates(NONE_TREND, 4.021, 0.889, 8.994, 10.990, 0.980, 0.818, 0.059, 4.014, 0.0, 5.346, 1.0, 1.0, 0.0),
                gates(NONE_TREND, 4.021, 2.019, 3.995, 10.993, 1.000, 0.818, 0.050, 4.015, 0.0, 9.634, 1.0, 1.0, 0.0),
            )
        val DATAGRAM =
            Gates(
                gates(SHARED_TURNS, 1.993, 1.540, 2.982, 3.005, 0.937, 1.000, 0.018, 0.018, 0.0, 7.103, 1.0, 1.0, 0.0),
                gates(SHARED_TURNS, 2.000, 1.549, 2.996, 3.298, 0.999, 1.000, 0.014, 0.012, 0.0, 7.129, 1.0, 1.0, 0.0),
            )

        /**
         * A bulk transfer's counts per MiB follow how many datagrams each wake finds, which the host's load decides: one tree
         * measured io-sends of 385 (macOS CI), 1,950 (an M-series laptop), 1,130 and then 2,487 (Windows CI) per MiB. No
         * margin holds that, so they are trended only. A one-off deadline must stay at zero and is gated; a fresh pool buffer is
         * not: the counted transfer can hold one buffer more at once than the warm-up did (Linux CI, callers in place).
         */
        val BULK_ZERO = setOf(Metric.DeadlinesMade)
        val BULK_EACH = Metric.entries.associateWith { if (it in BULK_ZERO) Gate.AtMost(LEAST_MARGIN) else Gate.TrendOnly }
        val BULK = Gates(BULK_EACH, BULK_EACH)

        /** Each count's gate: [trend] trended only, every other the [measured] value plus [ECHO_MARGIN], at least [LEAST_MARGIN]. */
        fun gates(
            trend: Set<Metric>,
            vararg measured: Double,
        ): Map<Metric, Gate> {
            check(measured.size == Metric.entries.size) { "one measured value per metric" }
            return Metric.entries.associateWith { m ->
                val value = measured[m.ordinal]
                if (m in trend) Gate.TrendOnly else Gate.AtMost(maxOf(value * (1 + ECHO_MARGIN), value + LEAST_MARGIN))
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
