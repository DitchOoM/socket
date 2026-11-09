package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours

/**
 * SPIKE (perf/owned-loop-spike, never merged): M concurrent client connections to one in-process server,
 * each driven by its own application thread. Phase 1: 64-byte round trips on one long-lived stream per
 * connection. Phase 2: bulk, 4 MiB streams back to back per connection. Prints aggregate throughput,
 * per-connection p50/p99 and the process CPU used, for the dispatch setup named by -Dquic.spike.loop.
 *
 * args: certDir, connection counts (comma separated), warmup seconds, measure seconds
 */
fun main(args: Array<String>) {
    System.setProperty("bench.certDir", args[0])
    val counts = args.getOrElse(1) { "1,4,16,64" }.split(',').map { it.trim().toInt() }
    val warmupS = args.getOrElse(2) { "2" }.toLong()
    val measureS = args.getOrElse(3) { "5" }.toLong()
    println(
        "# setup: loop=${System.getProperty("quic.spike.loop") ?: "shared"} " +
            "pool.size=${System.getProperty("kotlinx.coroutines.scheduler.core.pool.size") ?: "default"} " +
            "cpus=${Runtime.getRuntime().availableProcessors()} backend=${loadedQuicheBackend()}",
    )
    for (m in counts) runLoad(m, warmupS, measureS)
    System.exit(0)
}

private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

private fun runLoad(
    m: Int,
    warmupS: Long,
    measureS: Long,
) {
    val serverReady = CompletableDeferred<Int>()
    val stop = CompletableDeferred<Unit>()
    val server =
        thread(name = "server", isDaemon = true) {
            runBlocking(Dispatchers.IO) {
                withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = loopbackTls(), quicOptions = QuicLoopback.OPTIONS) {
                    val job = launch { connections { sinkOrEchoForLoad() } }
                    serverReady.complete(port)
                    stop.await()
                    job.cancel()
                }
            }
        }
    val port = runBlocking { serverReady.await() }
    val clients =
        List(m) { i ->
            val ready = CompletableDeferred<QuicScope>()
            thread(name = "client-conn-$i", isDaemon = true) {
                try {
                    runBlocking(Dispatchers.IO) {
                        withQuicConnection("127.0.0.1", port, QuicLoopback.OPTIONS, timeout = 24.hours) {
                            ready.complete(this)
                            stop.await()
                        }
                    }
                } catch (t: Throwable) {
                    ready.completeExceptionally(t)
                }
            }
            ready
        }.map { runBlocking { it.await() } }

    // Phase 1: round trips.
    val phase = Phase(warmupS, measureS)
    val lat = Array(m) { LongArray(4_000_000) }
    val counts = IntArray(m)
    val workers =
        clients.mapIndexed { i, scope ->
            thread(name = "app-$i") {
                runBlocking {
                    val stream = scope.openStream()
                    val req = scope.bufferFactory.allocate(64)
                    req.writeByte(ECHO_MARKER)
                    repeat(63) { req.writeByte('r'.code.toByte()) }
                    req.resetForRead()
                    while (!phase.done()) {
                        val t0 = System.nanoTime()
                        req.position(0)
                        stream.writeFully(req, IO_TIMEOUT)
                        stream.readExactly(64)
                        val dt = System.nanoTime() - t0
                        if (phase.measuring() && counts[i] < lat[i].size) lat[i][counts[i]++] = dt
                    }
                    stream.shutdownSend()
                    stream.close()
                }
            }
        }
    phase.start()
    Thread.sleep((warmupS + measureS / 2) * 1000)
    println("THREADS M=%3d  %s".format(m, threadCensus()))
    workers.forEach { it.join() }
    val p50s = mutableListOf<Double>()
    val p99s = mutableListOf<Double>()
    var max = 0L
    for (i in 0 until m) {
        val a = lat[i].copyOf(counts[i]).also { it.sort() }
        if (a.isEmpty()) continue
        p50s += a[a.size / 2] / 1000.0
        p99s += a[(a.size * 0.99).toInt().coerceAtMost(a.size - 1)] / 1000.0
        max = maxOf(max, a.last())
    }
    p50s.sort()
    p99s.sort()
    val total = counts.sum()
    println(
        "RT     M=%3d  agg %9.0f rt/s  per-conn p50 med %7.1f us  p99 med %7.1f us  p99 worst %8.1f us  max %8.1f us  cpu %5.2f cores"
            .format(m, total / phase.seconds(), p50s[p50s.size / 2], p99s[p99s.size / 2], p99s.last(), max / 1000.0, phase.cores()),
    )

    // Phase 2: bulk.
    val bulkPhase = Phase(warmupS, measureS)
    val bytes = LongArray(m)
    val bulkWorkers =
        clients.mapIndexed { i, scope ->
            thread(name = "bulk-$i") {
                runBlocking {
                    val chunk = scope.bufferFactory.allocate(64 * 1024)
                    repeat(64 * 1024) { chunk.writeByte('D'.code.toByte()) }
                    while (!bulkPhase.done()) {
                        val stream = scope.openStream()
                        try {
                            repeat(64) {
                                chunk.position(0)
                                chunk.setLimit(64 * 1024)
                                stream.writeFully(chunk, IO_TIMEOUT)
                            }
                            stream.shutdownSend()
                            stream.readToEnd()
                        } finally {
                            stream.close()
                        }
                        if (bulkPhase.measuring()) bytes[i] += 4L * 1024 * 1024
                    }
                }
            }
        }
    bulkPhase.start()
    bulkWorkers.forEach { it.join() }
    println(
        "BULK   M=%3d  agg %8.1f MiB/s  cpu %5.2f cores".format(m, bytes.sum() / 1048576.0 / bulkPhase.seconds(), bulkPhase.cores()),
    )
    stop.complete(Unit)
    server.join(10_000)
}

/** Warm-up then a measured window; CPU is the process CPU time over the measured window. */
private class Phase(
    private val warmupS: Long,
    private val measureS: Long,
) {
    @Volatile private var startNs = Long.MAX_VALUE

    @Volatile private var cpuAtMeasure = 0L

    @Volatile private var cpuAtEnd = 0L

    @Volatile private var ended = false

    fun start() {
        startNs = System.nanoTime()
        thread(isDaemon = true) {
            Thread.sleep(warmupS * 1000)
            cpuAtMeasure = os.processCpuTime
            Thread.sleep(measureS * 1000)
            cpuAtEnd = os.processCpuTime
            ended = true
        }
    }

    fun measuring(): Boolean = System.nanoTime() - startNs >= warmupS * 1_000_000_000 && !ended

    fun done(): Boolean = ended

    fun seconds(): Double = measureS.toDouble()

    fun cores(): Double = (cpuAtEnd - cpuAtMeasure) / 1e9 / measureS
}

/** Live threads by kind, excluding this harness's own application and connection-holder threads. */
internal fun threadCensus(): String {
    val kinds = java.util.TreeMap<String, Int>()
    for (t in Thread.getAllStackTraces().keys) {
        val n = t.name
        val kind =
            when {
                n.startsWith("app-") || n.startsWith("bulk-") || n.startsWith("client-conn-") || n == "server" -> continue
                n.startsWith("DefaultDispatcher-worker") -> "kotlinx-pool"
                n.startsWith("quic-owned-loop") -> "owned-loop"
                n.startsWith("quic-selector-loop") -> "selector-loop"
                n.startsWith("quic-deadline-timer") -> "deadline-timer"
                n.startsWith("kotlinx.coroutines.DefaultExecutor") -> "kotlinx-DefaultExecutor"
                else -> "other"
            }
        kinds[kind] = (kinds[kind] ?: 0) + 1
    }
    return kinds.entries.joinToString(" ") { "${it.key}=${it.value}" }
}
