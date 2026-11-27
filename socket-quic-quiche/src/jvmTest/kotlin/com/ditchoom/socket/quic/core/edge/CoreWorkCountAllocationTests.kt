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
import com.ditchoom.socket.quic.core.testing.ClockSite
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
 * that land within one pass share it, and a loaded host shares fewer. So a socket receive that brought datagrams is
 * gated per operation, each carrying at least one of the echo's; one that found the socket drained ends the reading of
 * a pass that read datagrams first, so it is held to at most one per receive that brought them. A pass waits at most
 * once and a serve re-arms the timer at most once. The clock is read at sites each bounded by the work that makes them
 * ([ClockSite]), counted in an extra run that files every reading by its site: two a pass, one a turn's start and one
 * a write's per serve, one per receive that brought datagrams, per datagram in and per datagram out, and an operation's
 * own readings, gated per operation. The passes and serves themselves are gated per operation only where an echo's
 * events never share one (see [SHARED_TURNS]). Each operation runs under
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
        val emptyReceives = SeamCount()
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
            emptyReceives.attribution = on
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
                    wrapIo = { CountingIo(it, seams.sends, seams.receives, seams.emptyReceives) },
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
                Metric.EmptyReceives to seams.emptyReceives.value(),
                Metric.Passes to client.loop.counts.passes,
                Metric.Serves to client.conn.counts.timer.serves,
                Metric.Waits to client.loop.counts.waits,
                Metric.TimerRearms to client.conn.counts.timer.rearms,
                Metric.DeadlineMoves to client.conn.counts.timer.moves,
                Metric.RearmsTaken to client.loop.port.rearmsTaken(),
                Metric.DeadlinesMade to client.loop.port.deadlinesMade(),
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
        repeat(warmUp) { once() }
        val before = Snapshot(client, seams)
        repeat(measured) { once() }
        val after = Snapshot(client, seams)
        val delta = Metric.entries.associateWith { after.values.getValue(it) - before.values.getValue(it) }
        val units =
            Metric.entries.associateWith { m ->
                when (m.per) {
                    Per.Op -> measured.toLong()
                    Per.Pass -> delta.getValue(Metric.Passes)
                    Per.Serve -> delta.getValue(Metric.Serves)
                    Per.Receive -> delta.getValue(Metric.Receives)
                }.coerceAtLeast(1L)
            }
        val perOp = Metric.entries.associateWith { m -> delta.getValue(m).toDouble() / units.getValue(m) }
        // A law of one each holds to the count, but for the few increments a counter read between operations is stale by.
        val gates =
            Metric.entries.associateWith { m ->
                when (m.bound) {
                    Bound.Measured -> gatesByRule.of(rule).getValue(m)
                    Bound.OneEach ->
                        when (val g = gatesByRule.of(rule).getValue(m)) {
                            is Gate.AtMost -> Gate.AtMost(g.limit + IN_FLIGHT.toDouble() / units.getValue(m))
                            is Gate.TrendOnly -> g
                        }
                }
            }
        val clock = clockSites(client, seams, name, classified(measured), gatesByRule.opReads(rule), once)
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
        println("[core work counts: $name]\n$table\n${clock.table}\n$sites")
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
        clock.record(test)
        val over = Metric.entries.filter { over(gates.getValue(it), perOp.getValue(it)).isNotEmpty() }.map { it.label } + clock.over()
        assertTrue(over.isEmpty(), "$name: ${over.joinToString()} over the gate\n$table\n${clock.table}\n$sites")
    }

    /**
     * Runs [once] [operations] times with every clock reading filed by its [ClockSite], and holds each site to its
     * bound in the work counted in the same run; the readings an operation makes itself are held to [opReads] each.
     */
    private suspend fun clockSites(
        client: CoreClient,
        seams: Seams,
        name: String,
        operations: Int,
        opReads: Gate,
        once: suspend () -> Unit,
    ): ClockReadings {
        val sitesFrom = seams.clock.sites.values()
        val from = Snapshot(client, seams)
        val inFrom = client.loop.counts.datagramsIn
        val outFrom = client.loop.counts.datagramsOut
        seams.clock.sites.classifying = Attribution.On
        repeat(operations) { once() }
        seams.clock.sites.classifying = Attribution.Off
        val to = Snapshot(client, seams)

        fun done(m: Metric) = to.values.getValue(m) - from.values.getValue(m)
        val work =
            SiteWork(
                operations = operations.toLong(),
                passes = done(Metric.Passes),
                serves = done(Metric.Serves),
                receives = done(Metric.Receives),
                datagramsIn = client.loop.counts.datagramsIn - inFrom,
                datagramsOut = client.loop.counts.datagramsOut - outFrom,
            )
        val read =
            seams.clock.sites
                .values()
                .mapValues { it.value - sitesFrom.getValue(it.key) }
        return ClockReadings(name, read, work, opReads)
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

    private fun classified(measured: Int) = maxOf(1, minOf(CLASSIFIED, measured))

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
        private val opReadsByLoop: Gate,
        private val opReadsInPlace: Gate,
    ) {
        fun of(rule: SendRule): Map<Metric, Gate> =
            when (rule) {
                is SendRule.ByLoop -> byLoop
                is SendRule.InPlace -> inPlace
            }

        /** The clock readings an operation makes itself ([ClockSite.Op]), each. */
        fun opReads(rule: SendRule): Gate =
            when (rule) {
                is SendRule.ByLoop -> opReadsByLoop
                is SendRule.InPlace -> opReadsInPlace
            }
    }

    /** The work a classified run did, which bounds each [ClockSite]'s readings. */
    private class SiteWork(
        val operations: Long,
        val passes: Long,
        val serves: Long,
        val receives: Long,
        val datagramsIn: Long,
        val datagramsOut: Long,
    )

    /** The clock readings of a classified run of [name], by [ClockSite], against each site's bound in [work]. */
    private class ClockReadings(
        private val name: String,
        private val read: Map<ClockSite, Long>,
        private val work: SiteWork,
        private val opReads: Gate,
    ) {
        /** The most readings [site] may make in [work]; an operation's own readings, [opReads] each. */
        private fun most(site: ClockSite): Gate =
            when (site) {
                ClockSite.Pass -> Gate.AtMost(PASS_READS * work.passes + IN_FLIGHT)
                ClockSite.Turn, ClockSite.Write -> Gate.AtMost((work.serves + IN_FLIGHT).toDouble())
                ClockSite.SentAt -> Gate.AtMost((work.datagramsOut + IN_FLIGHT).toDouble())
                ClockSite.Judge -> Gate.AtMost(JUDGED_ONCE)
                ClockSite.Receive -> Gate.AtMost((work.receives + IN_FLIGHT).toDouble())
                ClockSite.DatagramIn -> Gate.AtMost((work.datagramsIn + IN_FLIGHT).toDouble())
                ClockSite.Op ->
                    when (opReads) {
                        is Gate.AtMost -> Gate.AtMost(opReads.limit * work.operations)
                        is Gate.TrendOnly -> opReads
                    }
            }

        private fun overOf(site: ClockSite): Over =
            when (val most = most(site)) {
                is Gate.AtMost -> if (read.getValue(site) > most.limit) Over.Yes else Over.No
                is Gate.TrendOnly -> Over.No
            }

        val table: String =
            "  clock readings by site, over ${work.operations} more operations (${work.passes} passes, ${work.serves} serves, " +
                "${work.receives} receives, ${work.datagramsIn} datagrams in, ${work.datagramsOut} out):\n" +
                ClockSite.entries.joinToString("\n") { site ->
                    "    %-11s %8d  (%s)%s".format(site, read.getValue(site), most(site), if (overOf(site) == Over.Yes) "  OVER" else "")
                }

        fun over(): List<String> = ClockSite.entries.filter { overOf(it) == Over.Yes }.map { "clock-reads at $it" }

        /** Each site's readings as a share of its bound, and the operation's own readings each. */
        fun record(test: String) {
            ClockSite.entries.forEach { site ->
                PerfGates.record(
                    CoreWorkCountAllocationTests::class.java,
                    test,
                    "clock-reads-${site.name.lowercase()}",
                    read.getValue(site) / most(site).limit(),
                    1.0,
                    "of-bound",
                )
            }
            PerfGates.record(
                CoreWorkCountAllocationTests::class.java,
                test,
                "clock-reads-op-each",
                read.getValue(ClockSite.Op).toDouble() / work.operations,
                opReads.limit(),
                "per-op",
            )
        }

        override fun toString() = name
    }

    private enum class Over { Yes, No }

    /** What a count is divided by: the operations, the loop's passes or the connection's serves in them, or the receives that brought datagrams. */
    private enum class Per(
        val unit: String,
    ) {
        Op("op"),
        Pass("pass"),
        Serve("serve"),
        Receive("recv"),
    }

    /** What holds a count: the most measured plus a margin, or a law of at most one per [Per] unit. */
    private enum class Bound { Measured, OneEach }

    private enum class Metric(
        val label: String,
        val per: Per,
        val bound: Bound = Bound.Measured,
    ) {
        Sends("io-sends", Per.Op),

        /** A receive that brought datagrams: at most one per datagram the echo's peer sent. */
        Receives("io-receives", Per.Op),

        /** A receive that found the socket drained, which ends a pass's reading: at most one per receive that brought datagrams. */
        EmptyReceives("io-empty-receives", Per.Receive, Bound.OneEach),
        Passes("loop-passes", Per.Op),
        Serves("conn-serves", Per.Op),

        /** At most one a pass: a pass that finds its input already waiting does not wait. */
        Waits("poll-waits", Per.Pass, Bound.OneEach),

        /** At most one a serve: a serve that collected everything re-arms the timer once. */
        TimerRearms("timer-rearms", Per.Serve, Bound.OneEach),
        DeadlineMoves("deadline-moves", Per.Op),
        RearmsTaken("rearms-taken", Per.Op),
        DeadlinesMade("deadlines-made", Per.Op),
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
        const val CLASSIFIED = 2_000

        /** The increments a count read between operations may lag or lead its work by. */
        const val IN_FLIGHT = 2L
        const val CHUNK = 65_536
        const val BULK_MIB = 16
        const val POOL_BUFFERS = 256
        const val POOL_MIN_BUFFER = 2_048
        const val TIMEOUT_SECONDS = 120L

        /** An echo's gate is what it measured plus a tenth, and never less than one call in twenty operations more. */
        const val ECHO_MARGIN = 0.10
        const val LEAST_MARGIN = 0.05

        /** The counts a [Bound.Measured] gate is given a value for, in the order of [Metric]. */
        val MEASURED_METRICS = Metric.entries.filter { it.bound == Bound.Measured }

        /** Each pass and serve of an echo is its own: the passes and serves per operation are gated. */
        val NONE_TREND = emptySet<Metric>()

        /** A datagram echo's two arrivals share a pass, or do not, as the host's load has it: 2.6 to 3.3 serves an echo. */
        val SHARED_TURNS = setOf(Metric.Passes, Metric.Serves)

        /**
         * The highest of every run measured, in the order of [Metric] less [Bound.OneEach]: sends, receives that brought
         * datagrams, passes, serves, deadline moves, re-arms taken, deadlines made, pool acquires, releases, misses; then an
         * operation's own clock readings, the loop sending and callers in place. The per-op counts: macOS arm64, JDK 21, JNI and FFM, native and JDK stacks, and macOS CI, the loop sending;
         * those, Linux x64 CI (JDK 17, 21, 25; JNI, FFM) and Windows CI, callers in place. The per-pass and per-serve counts,
         * the receives that brought datagrams, the passes and serves, and the operation's clock readings: macOS arm64, JDK 21,
         * JNI and FFM, idle and with every core busy, never above the packets an echo brings.
         */
        val ONE_STREAM =
            Gates(
                gates(NONE_TREND, 2.000, 1.993, 3.968, 4.959, 0.018, 2.005, 0.0, 1.0, 1.0, 0.0),
                gates(NONE_TREND, 2.000, 1.998, 1.997, 4.996, 0.021, 2.005, 0.0, 1.0, 1.0, 0.0),
                withMargin(1.002),
                withMargin(2.943),
            )
        val NEW_STREAM =
            Gates(
                gates(NONE_TREND, 4.021, 3.998, 8.994, 10.990, 0.059, 4.014, 0.0, 1.0, 1.0, 0.0),
                gates(NONE_TREND, 4.021, 3.999, 3.995, 10.993, 0.050, 4.015, 0.0, 1.0, 1.0, 0.0),
                withMargin(2.015),
                withMargin(6.856),
            )
        val DATAGRAM =
            Gates(
                gates(SHARED_TURNS, 1.993, 1.927, 2.982, 3.005, 0.018, 0.018, 0.0, 1.0, 1.0, 0.0),
                gates(SHARED_TURNS, 2.000, 1.908, 2.996, 3.298, 0.014, 0.012, 0.0, 1.0, 1.0, 0.0),
                withMargin(2.020),
                withMargin(2.994),
            )

        /**
         * A bulk transfer's counts per MiB follow how many datagrams each wake finds, which the host's load decides: one tree
         * measured io-sends of 385 (macOS CI), 1,950 (an M-series laptop), 1,130 and then 2,487 (Windows CI) per MiB. No
         * margin holds that, so they are trended only. A one-off deadline must stay at zero and is gated; a fresh pool buffer is
         * not: the counted transfer can hold one buffer more at once than the warm-up did (Linux CI, callers in place).
         */
        val BULK_ZERO = setOf(Metric.DeadlinesMade)
        val BULK_EACH =
            Metric.entries.associateWith {
                when {
                    it in BULK_ZERO -> Gate.AtMost(LEAST_MARGIN)
                    it.bound == Bound.OneEach -> Gate.AtMost(1.0)
                    else -> Gate.TrendOnly
                }
            }
        val BULK = Gates(BULK_EACH, BULK_EACH, Gate.TrendOnly, Gate.TrendOnly)

        /** A pass reads the clock after it and after its wait, which is the next pass's time. */
        const val PASS_READS = 2.0

        /** The connection's first datagram judges whose clock libquiche reads, once. */
        const val JUDGED_ONCE = 1.0

        /** [value] plus [ECHO_MARGIN], at least [LEAST_MARGIN]. */
        fun withMargin(value: Double): Gate = Gate.AtMost(maxOf(value * (1 + ECHO_MARGIN), value + LEAST_MARGIN))

        /**
         * Each count's gate: [trend] trended only, a [Bound.OneEach] count at most one, every other the [measured] value (one
         * per [MEASURED_METRICS]) plus [ECHO_MARGIN], at least [LEAST_MARGIN].
         */
        fun gates(
            trend: Set<Metric>,
            vararg measured: Double,
        ): Map<Metric, Gate> {
            check(measured.size == MEASURED_METRICS.size) { "one measured value per measured metric" }
            return Metric.entries.associateWith { m ->
                when {
                    m in trend -> Gate.TrendOnly
                    m.bound == Bound.OneEach -> Gate.AtMost(1.0)
                    else -> withMargin(measured[MEASURED_METRICS.indexOf(m)])
                }
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
