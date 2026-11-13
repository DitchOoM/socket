package com.ditchoom.socket.benchmark.slice

import com.ditchoom.socket.benchmark.ECHO_MARKER
import com.ditchoom.socket.benchmark.assertQuicheBackend
import io.netty.buffer.PooledByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.WriteBufferWaterMark
import io.netty.handler.codec.quic.QuicStreamChannel
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/*
 * Plain programs over the slice (JMH has no parameterized thread count):
 *
 *   SliceLoadKt    M connections, each driven by an application thread of its own: 64-byte round trips on one
 *                  long-lived stream per connection (the RT phase of QuicMultiConnectionLoad).
 *   SliceStressKt  the test of the one-thread-inside invariant: many streams on few connections, every echoed
 *                  byte checked, every entry into a connection counted.
 */

/** Requests that ended without their whole reply (a stall, a reply timeout or a stream that ended early). */
internal val incompleteOps = AtomicLong()

private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
private val threadsBean = ManagementFactory.getThreadMXBean()

/** CPU time in nanoseconds of [threads], summed. */
internal fun cpuOf(threads: List<Thread>): Long = threads.sumOf { threadsBean.getThreadCpuTime(it.threadId()).coerceAtLeast(0) }

/** Warm-up then a measured window, with process CPU and the CPU of chosen threads over the measured window. */
internal class LoadWindow(
    private val warmupS: Long,
    private val measureS: Long,
    private val serverThreads: () -> List<Thread>,
    private val clientLoopThreads: () -> List<Thread> = { emptyList() },
    private val appThreads: () -> List<Thread> = { emptyList() },
) {
    @Volatile private var clientLoopCpu = 0L

    /** Wall-clock bounds of the measured window, for lining a client up with a server in another process. */
    @Volatile var measuredFromEpochMs = 0L

    @Volatile var measuredToEpochMs = 0L

    @Volatile private var appCpu = 0L

    fun clientLoopCpuNanos(): Long = clientLoopCpu

    fun appCpuNanos(): Long = appCpu

    @Volatile private var startNs = Long.MAX_VALUE

    @Volatile private var processCpu = 0L

    @Volatile private var serverCpu = 0L

    @Volatile private var ended = false

    /** Starts the warm-up now, or at [atEpochMs] when that is later (so two clients measure the same window). */
    fun start(atEpochMs: Long = 0L) {
        val wait = (atEpochMs - System.currentTimeMillis()).coerceAtLeast(0)
        startNs = System.nanoTime() + wait * 1_000_000
        thread(isDaemon = true) {
            Thread.sleep(wait + warmupS * 1000)
            measuredFromEpochMs = System.currentTimeMillis()
            val p0 = os.processCpuTime
            val s0 = cpuOf(serverThreads())
            val c0 = cpuOf(clientLoopThreads())
            val a0 = cpuOf(appThreads())
            Thread.sleep(measureS * 1000)
            measuredToEpochMs = System.currentTimeMillis()
            processCpu = os.processCpuTime - p0
            serverCpu = cpuOf(serverThreads()) - s0
            clientLoopCpu = cpuOf(clientLoopThreads()) - c0
            appCpu = cpuOf(appThreads()) - a0
            ended = true
        }
    }

    fun measuring(): Boolean = System.nanoTime() - startNs >= warmupS * 1_000_000_000 && !ended

    fun done(): Boolean = ended

    fun seconds(): Double = measureS.toDouble()

    fun processCpuNanos(): Long = processCpu

    fun serverCpuNanos(): Long = serverCpu
}

/** Runs [worker] on [m] application threads through a warm-up and a measured window and prints one line. */
internal fun latencyLoad(
    label: String,
    m: Int,
    warmupS: Long,
    measureS: Long,
    serverThreads: () -> List<Thread>,
    clientLoopThreads: () -> List<Thread> = { emptyList() },
    startAtEpochMs: Long = 0L,
    worker: (index: Int, record: (nanos: Long) -> Unit, done: () -> Boolean) -> Unit,
) {
    val apps = java.util.concurrent.ConcurrentLinkedQueue<Thread>()
    val window = LoadWindow(warmupS, measureS, serverThreads, clientLoopThreads) { apps.toList() }
    val lat = Array(m) { LongArray((40_000_000 / m).coerceIn(20_000, 1_500_000)) }
    val counts = IntArray(m)
    val workers =
        List(m) { i ->
            thread(name = "load-app-$i") {
                apps.add(Thread.currentThread())
                worker(
                    i,
                    { dt -> if (window.measuring() && counts[i] < lat[i].size) lat[i][counts[i]++] = dt },
                    window::done,
                )
            }
        }
    window.start(startAtEpochMs)
    println("# window starts")
    System.out.flush()
    workers.forEach { it.join() }
    val p50s = mutableListOf<Double>()
    val p99s = mutableListOf<Double>()
    val pooled = LongArray(counts.sum())
    var filled = 0
    for (i in 0 until m) {
        val a = lat[i].copyOf(counts[i]).also { it.sort() }
        System.arraycopy(a, 0, pooled, filled, a.size)
        filled += a.size
        if (a.isEmpty()) continue
        p50s += a[a.size / 2] / 1000.0
        p99s += a[(a.size * 0.99).toInt().coerceAtMost(a.size - 1)] / 1000.0
    }
    p50s.sort()
    p99s.sort()
    val total = counts.sum().coerceAtLeast(1)
    println(
        "%-10s M=%4d  agg %9.0f op/s  per-conn p50 med %8.1f us  p99 med %8.1f us  p99 worst %9.1f us  process cpu %7.1f us/op  server cpu %7.1f us/op  client loops %7.1f us/op  app threads %7.1f us/op  (%5.2f cores)"
            .format(
                label,
                m,
                total / window.seconds(),
                p50s.getOrElse(p50s.size / 2) { Double.NaN },
                p99s.getOrElse(p99s.size / 2) { Double.NaN },
                p99s.lastOrNull() ?: Double.NaN,
                window.processCpuNanos() / 1000.0 / total,
                window.serverCpuNanos() / 1000.0 / total,
                window.clientLoopCpuNanos() / 1000.0 / total,
                window.appCpuNanos() / 1000.0 / total,
                window.processCpuNanos() / 1e9 / window.seconds(),
            ),
    )
    pooled.sort()

    fun pct(p: Double) = if (pooled.isEmpty()) Double.NaN else pooled[(pooled.size * p).toInt().coerceAtMost(pooled.size - 1)] / 1000.0
    println(
        "window %d %d ops %d pooled p50 %.1f us p99 %.1f us p99.9 %.1f us max %.1f us incomplete %d"
            .format(window.measuredFromEpochMs, window.measuredToEpochMs, counts.sum(), pct(0.50), pct(0.99), pct(0.999), pct(1.0), incompleteOps.get()),
    )
}

/**
 * args: connection counts (comma separated), warm-up seconds, measured seconds, placement (loop|owned|ownedq),
 * callerRuns (true|false), client loops. `-Dslice.probe=true` adds the hand-off probe (not for timed rows).
 */
fun main(args: Array<String>) {
    val ms = args.getOrElse(0) { "1,64" }.split(',').map { it.trim().toInt() }
    val warmupS = args.getOrElse(1) { "3" }.toLong()
    val measureS = args.getOrElse(2) { "8" }.toLong()
    val placement = args.getOrElse(3) { "loop" }
    val callerRuns = args.getOrElse(4) { "false" }.toBoolean()
    val clientLoops = args.getOrElse(5) { "4" }.toInt()
    val hold = args.getOrElse(6) { "true" }.toBoolean()
    val mode = args.getOrElse(7) { "rt" }
    println("# slice load: mode=$mode placement=$placement callerRuns=$callerRuns clientLoops=$clientLoops hold=$hold real=${System.getProperty("slice.real") ?: "off"} cpus=${Runtime.getRuntime().availableProcessors()} backend=${assertQuicheBackend()}")
    for (m in ms) {
        val loopback = SliceLoopback(placement, holdAck = hold, callerRuns = callerRuns, connectionCount = m, clientLoops = minOf(clientLoops, m))
        val ops = AtomicLong()
        latencyLoad("slice", m, warmupS, measureS, loopback::serverThreads, loopback::clientLoopThreads) { i, record, done ->
            runBlocking {
                val conn = loopback.connections[i]
                val request = filledRequest(64)
                var n = 0L
                if (mode == "rt") {
                    val stream = conn.openStream()
                    while (!done()) {
                        val t0 = System.nanoTime()
                        request.position = 0
                        stream.write(request)
                        val got = stream.readExactly(64)
                        record(System.nanoTime() - t0)
                        n++
                        if (got != 64) {
                            incompleteOps.incrementAndGet()
                            break
                        }
                    }
                } else {
                    while (!done()) {
                        val t0 = System.nanoTime()
                        val stream = conn.openStream()
                        request.position = 0
                        stream.write(request)
                        stream.shutdownSend()
                        val got = stream.readToEnd()
                        record(System.nanoTime() - t0)
                        n++
                        if (got != 64) incompleteOps.incrementAndGet()
                    }
                }
                ops.addAndGet(n)
            }
        }
        println(loopback.report(ops.get()))
        loopback.close()
    }
    System.exit(0)
}

/**
 * The stress test of the invariant "at most one thread inside a connection at a time".
 *
 * [streams] application threads per connection each drive one stream: a message of a random length up to
 * [maxBytes] (so writes are taken in parts and replies span many datagrams) whose every byte is a function of
 * the stream, the message number and the offset; the echo is checked byte by byte as it arrives. Meanwhile the
 * server's handlers (placement owned, callerRuns on) write from handler threads while the loop feeds datagrams.
 * Run with -Dslice.check=true: every entry into a connection is counted and every quiche call asserts its
 * thread is the one inside.
 *
 * args: seconds, connections, streams per connection, max message bytes, placement, callerRuns.
 */
internal object SliceStress {
    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = args.getOrElse(0) { "20" }.toLong()
        val connections = args.getOrElse(1) { "2" }.toInt()
        val streams = args.getOrElse(2) { "32" }.toInt()
        val maxBytes = args.getOrElse(3) { "6000" }.toInt()
        val placement = args.getOrElse(4) { "owned" }
        val callerRuns = args.getOrElse(5) { "true" }.toBoolean()
        check(System.getProperty("slice.check") == "true") { "run the stress test with -Dslice.check=true" }
        val loopback = SliceLoopback(placement, holdAck = true, callerRuns = callerRuns, connectionCount = connections, clientLoops = minOf(2, connections))
        val messages = AtomicLong()
        val bytes = AtomicLong()
        val mismatches = AtomicLong()
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val workers =
            List(connections * streams) { w ->
                thread(name = "stress-$w") {
                    runBlocking {
                        val stream = loopback.connections[w % connections].openStream()
                        val out = NativeBuf(maxBytes + 16)
                        val random = java.util.Random(w.toLong())
                        var message = 0L
                        while (System.nanoTime() < deadline) {
                            val len = 1 + random.nextInt(maxBytes)
                            out.bb.put(0, ECHO_MARKER)
                            for (i in 1 until len) out.bb.put(i, expected(w, message, i))
                            out.position = 0
                            out.limit = len
                            stream.write(out)
                            var got = 0
                            while (got < len) {
                                val r =
                                    when (val result = stream.read()) {
                                        is NativeBuf -> result
                                        is SliceData -> result.buffer
                                        SliceEnd -> error("stream $w ended early")
                                    }
                                for (i in r.position until r.limit) {
                                    val want = if (got == 0) ECHO_MARKER else expected(w, message, got)
                                    if (r.bb.get(i) != want) mismatches.incrementAndGet()
                                    got++
                                }
                                stream.release(r)
                            }
                            if (got != len) mismatches.incrementAndGet()
                            message++
                            bytes.addAndGet(len.toLong())
                        }
                        messages.addAndGet(message)
                    }
                }
            }
        workers.forEach { it.join() }
        println(loopback.report(messages.get()))
        println(
            "# stress: placement=$placement callerRuns=$callerRuns connections=$connections streams/conn=$streams " +
                "messages=${messages.get()} bytes=${bytes.get()} mismatched bytes=${mismatches.get()} violations=${loopback.violations()}",
        )
        val ok = mismatches.get() == 0L && loopback.violations() == 0L && messages.get() > 0
        println(if (ok) "STRESS PASSED" else "STRESS FAILED")
        loopback.close()
        System.exit(if (ok) 0 else 1)
    }

    private fun expected(
        stream: Int,
        message: Long,
        offset: Int,
    ): Byte = (stream * 31 + message * 131 + offset * 7 + (offset ushr 8)).toByte()
}

/**
 * The same load on Netty QUIC: M connections, each on a socket of its own, 64-byte round trips on one stream
 * per connection. `-Dbench.nettyHandlerExecutor=true` moves the handler off the event loop.
 *
 * args: connection counts (comma separated), warm-up seconds, measured seconds, client event loops.
 */
internal object NettyLoad {
    @JvmStatic
    fun main(args: Array<String>) {
        val ms = args.getOrElse(0) { "1,64" }.split(',').map { it.trim().toInt() }
        val warmupS = args.getOrElse(1) { "3" }.toLong()
        val measureS = args.getOrElse(2) { "8" }.toLong()
        val clientLoops = args.getOrElse(3) { "4" }.toInt()
        val mode = args.getOrElse(4) { "rt" }
        println("# netty load: mode=$mode handlerExecutor=${System.getProperty("bench.nettyHandlerExecutor") == "true"} clientLoops=$clientLoops cpus=${Runtime.getRuntime().availableProcessors()}")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            e.printStackTrace()
            Runtime.getRuntime().halt(1)
        }
        for (m in ms) {
            val loopback = com.ditchoom.socket.benchmark.NettyQuicLoopback(m, minOf(clientLoops, m))
            latencyLoad("netty", m, warmupS, measureS, loopback::serverThreads, loopback::clientLoopThreads) { i, record, done ->
                val request = com.ditchoom.socket.benchmark.nettyFilledRequest(64)
                if (mode == "rt") {
                    val (stream, sink) = loopback.openStream(i)
                    while (!done()) {
                        val t0 = System.nanoTime()
                        sink.reset()
                        stream.writeAndFlush(request.retainedDuplicate())
                        val got = runCatching { sink.awaitBytes(64) }.getOrDefault(-1)
                        record(System.nanoTime() - t0)
                        if (got != 64) {
                            incompleteOps.incrementAndGet()
                            break
                        }
                    }
                    stream.close()
                } else {
                    while (!done()) {
                        val t0 = System.nanoTime()
                        val (stream, sink) = loopback.openStream(i)
                        stream.writeAndFlush(request.retainedDuplicate()).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                        val got = runCatching { sink.awaitFinished() }.getOrDefault(-1)
                        stream.close().awaitUninterruptibly(10, java.util.concurrent.TimeUnit.SECONDS)
                        record(System.nanoTime() - t0)
                        if (got != 64) incompleteOps.incrementAndGet()
                    }
                }
                request.release()
            }
            loopback.close()
        }
        System.exit(0)
    }
}

private fun percentiles(samples: LongArray, n: Int): String {
    if (n == 0) return "no samples"
    val a = samples.copyOf(n).also { it.sort() }
    return "n=%d p50 %.1f us  p99 %.1f us  worst %.1f us".format(n, a[n / 2] / 1000.0, a[(n * 0.99).toInt().coerceAtMost(n - 1)] / 1000.0, a[n - 1] / 1000.0)
}

/** Round trips back to back on [op] from the moment [from] is set until [until]; returns what they took. */
private class Victim(
    val label: String,
) {
    val samples = LongArray(4_000_000)

    @Volatile var n = 0
}

/**
 * The safety column: one stream is served by a handler that blocks its thread for 100 ms per request, back to
 * back; meanwhile three victims run 64-byte round trips back to back and report what they saw:
 * (1) another stream on the blocked handler's connection, (2) another connection on the same server loop,
 * (3) a connection on the server's other loop.
 *
 * args: placement, callerRuns (client), seconds.
 */
internal object SliceSafety {
    @JvmStatic
    fun main(args: Array<String>) {
        val placement = args.getOrElse(0) { "loop" }
        val callerRuns = args.getOrElse(1) { "true" }.toBoolean()
        val seconds = args.getOrElse(2) { "5" }.toLong()
        // Connections 0 and 2 are on the first server loop, connection 1 on the second.
        val loopback = SliceLoopback(placement, holdAck = true, callerRuns = callerRuns, connectionCount = 3, clientLoops = 3, serverLoops = 2)
        val victims = listOf(Victim("(1) same connection"), Victim("(2) same loop"), Victim("(3) other loop"))
        val conns = listOf(loopback.connections[0], loopback.connections[2], loopback.connections[1])
        val blockedFrom = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val end = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val threads =
            victims.mapIndexed { i, v ->
                thread(name = "victim-$i") {
                    runBlocking {
                        val stream = conns[i].openStream()
                        val request = filledRequest(64)
                        while (true) {
                            val t0 = System.nanoTime()
                            if (t0 >= end.get()) break
                            request.position = 0
                            stream.write(request)
                            stream.readExactly(64)
                            val t1 = System.nanoTime()
                            if (t0 >= blockedFrom.get() && v.n < v.samples.size) v.samples[v.n++] = t1 - t0
                        }
                    }
                }
            }
        Thread.sleep(1000)
        var blocks = 0
        val blocker =
            thread(name = "blocker") {
                runBlocking {
                    val stream = loopback.connections[0].openStream()
                    val request = filledRequest(64)
                    request.bb.put(0, BLOCK_MARKER)
                    blockedFrom.set(System.nanoTime())
                    val stopAt = System.nanoTime() + seconds * 1_000_000_000L
                    while (System.nanoTime() < stopAt) {
                        request.position = 0
                        stream.write(request)
                        stream.readExactly(64)
                        blocks++
                    }
                    end.set(System.nanoTime())
                }
            }
        blocker.join()
        threads.forEach { it.join(3000) }
        println("# slice safety: placement=$placement callerRuns=$callerRuns blocked requests=$blocks takeovers=${loopback.takeovers()} violations=${loopback.violations()}")
        for (v in victims) println("safety %-10s %-20s %s".format(placement, v.label, percentiles(v.samples, v.n)))
        Runtime.getRuntime().halt(0)
    }
}

/** The safety column on Netty QUIC. `-Dbench.nettyHandlerExecutor=true` moves the handler off the event loop. args: seconds. */
internal object NettySafety {
    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = args.getOrElse(0) { "5" }.toLong()
        val mode = if (System.getProperty("bench.nettyHandlerExecutor") == "true") "nettyExec" else "nettyLoop"
        val loopback = com.ditchoom.socket.benchmark.NettyQuicLoopback(3, 3, 2)
        val victims = listOf(Victim("(1) same connection"), Victim("(2) same loop"), Victim("(3) other loop"))
        val conns = listOf(0, 2, 1)
        val blockedFrom = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val end = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val threads =
            victims.mapIndexed { i, v ->
                thread(name = "victim-$i") {
                    val (stream, sink) = loopback.openStream(conns[i])
                    val request = com.ditchoom.socket.benchmark.nettyFilledRequest(64)
                    while (true) {
                        val t0 = System.nanoTime()
                        if (t0 >= end.get()) break
                        sink.reset()
                        stream.writeAndFlush(request.retainedDuplicate())
                        sink.awaitBytes(64)
                        val t1 = System.nanoTime()
                        if (t0 >= blockedFrom.get() && v.n < v.samples.size) v.samples[v.n++] = t1 - t0
                    }
                }
            }
        Thread.sleep(1000)
        var blocks = 0
        val blocker =
            thread(name = "blocker") {
                val (stream, sink) = loopback.openStream(0)
                val request = com.ditchoom.socket.benchmark.nettyFilledRequest(64)
                request.setByte(0, 'B'.code)
                blockedFrom.set(System.nanoTime())
                val stopAt = System.nanoTime() + seconds * 1_000_000_000L
                while (System.nanoTime() < stopAt) {
                    sink.reset()
                    stream.writeAndFlush(request.retainedDuplicate())
                    sink.awaitBytes(64)
                    blocks++
                }
                end.set(System.nanoTime())
            }
        blocker.join()
        threads.forEach { it.join(3000) }
        println("# netty safety: mode=$mode blocked requests=$blocks")
        for (v in victims) println("safety %-10s %-20s %s".format(mode, v.label, percentiles(v.samples, v.n)))
        Runtime.getRuntime().halt(0)
    }
}

/**
 * What the server's threads cost with no traffic: one connection, a hundred round trips, then nothing for
 * [seconds]; prints the CPU the server's threads used in that time. args: placement, seconds.
 */
internal object SliceIdle {
    @JvmStatic
    fun main(args: Array<String>) {
        val placement = args.getOrElse(0) { "affine" }
        val seconds = args.getOrElse(1) { "5" }.toLong()
        val loopback = SliceLoopback(placement, holdAck = true, callerRuns = true)
        runBlocking {
            val stream = loopback.connection.openStream()
            val request = filledRequest(64)
            repeat(100) {
                request.position = 0
                stream.write(request)
                stream.readExactly(64)
            }
        }
        Thread.sleep(200)
        val c0 = cpuOf(loopback.serverThreads())
        Thread.sleep(seconds * 1000)
        val used = cpuOf(loopback.serverThreads()) - c0
        println("idle %-8s server threads used %.3f ms of CPU in %d s (%.4f%% of a core)".format(placement, used / 1e6, seconds, used / 1e7 / seconds))
        Runtime.getRuntime().halt(0)
    }
}

/**
 * Bulk as a plain program, so a window can be lined up with netem: one connection, back-to-back streams of
 * [mib] MiB in 64 KiB writes, each ended by the server's FIN after it has read everything.
 * Prints MiB/s, process CPU per MiB and the streams that did not complete.
 *
 * args: impl (slice|netty), warm-up s, measured s, MiB per stream, then for the slice: placement, callerRuns, hold.
 */
internal object BulkLoad {
    @JvmStatic
    fun main(args: Array<String>) {
        val impl = args[0]
        val warmupS = args[1].toLong()
        val measureS = args[2].toLong()
        val mib = args[3].toInt()
        val chunks = mib * 16
        val window = LoadWindow(warmupS, measureS, { emptyList() })
        val bytes = AtomicLong()
        println("# bulk: $impl ${args.drop(4).joinToString(" ")} real=${System.getProperty("slice.real") ?: "off"}")
        val closeAll: () -> Unit
        val worker: () -> Unit
        if (impl == "slice") {
            val loopback = SliceLoopback(args[4], holdAck = args[6].toBoolean(), callerRuns = args[5].toBoolean())
            val chunk = NativeBuf(64 * 1024)
            for (i in 0 until chunk.capacity) chunk.bb.put(i, 'D'.code.toByte())
            worker = {
                runBlocking {
                    while (!window.done()) {
                        val stream = loopback.connection.openStream()
                        repeat(chunks) {
                            chunk.position = 0
                            chunk.limit = chunk.capacity
                            stream.write(chunk)
                        }
                        stream.shutdownSend()
                        val end = stream.readToEnd()
                        if (end != 0) incompleteOps.incrementAndGet()
                        if (window.measuring()) bytes.addAndGet(mib * 1048576L)
                    }
                }
            }
            closeAll = { println(loopback.report(1)); loopback.close() }
        } else {
            val loopback = com.ditchoom.socket.benchmark.NettyQuicLoopback()
            val chunk = PooledByteBufAllocator.DEFAULT.directBuffer(64 * 1024)
            repeat(64 * 1024) { chunk.writeByte('D'.code) }
            worker = {
                while (!window.done()) {
                    val (stream, sink) = loopback.openStream()
                    stream.config().writeBufferWaterMark = WriteBufferWaterMark(1024 * 1024, 4 * 1024 * 1024)
                    var ok = true
                    repeat(chunks) {
                        if (!stream.isWritable) ok = ok && runCatching { sink.awaitWritable(stream) }.isSuccess
                        stream.writeAndFlush(chunk.retainedDuplicate())
                    }
                    stream.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                    if (!ok || runCatching { sink.awaitFinished() }.isFailure) incompleteOps.incrementAndGet()
                    stream.close().awaitUninterruptibly(10, java.util.concurrent.TimeUnit.SECONDS)
                    if (window.measuring()) bytes.addAndGet(mib * 1048576L)
                }
            }
            closeAll = { loopback.close() }
        }
        val t = thread(name = "bulk") { worker() }
        window.start()
        println("# window starts")
        System.out.flush()
        t.join()
        val mibs = bytes.get() / 1048576.0
        println(
            "bulk %-6s %8.1f MiB/s  process cpu %8.1f us/MiB  streams incomplete %d"
                .format(impl, mibs / window.seconds(), window.processCpuNanos() / 1000.0 / mibs.coerceAtLeast(1.0), incompleteOps.get()),
        )
        closeAll()
        Runtime.getRuntime().halt(0)
    }
}
