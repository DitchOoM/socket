package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
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
 * M QUIC connections to one in-process server, each driven by an application thread of its own, which is
 * the shape of a process serving or holding many connections. JMH measures one connection well and many
 * poorly (a parameterized thread count is not something it offers), so this is a plain program.
 *
 * For each M it runs three phases, each a warm-up then a measured window:
 * - `RT`: 64-byte round trips on one long-lived stream per connection;
 * - `SPR`: stream per request (open, 64 bytes, FIN, read the echo to the server's FIN);
 * - `BULK`: 4 MiB streams back to back per connection.
 *
 * It prints aggregate operations per second, the median over connections of each connection's p50 and
 * p99 (and the worst p99), the slowest single operation, and the process CPU used, in cores.
 *
 *   ./gradlew :benchmark-jvm:multiConnectionLoad -PappleTargets=false [-PloadArgs="1,4,16,64 2 5"]
 *
 * args: connection counts (comma separated), warm-up seconds, measured seconds, phases (comma separated,
 * default `rt,spr,bulk`). The cert directory comes from `-Dbench.certDir`, as for the JMH benchmarks.
 */
fun main(args: Array<String>) {
    val counts = args.getOrElse(0) { "1,4,16,64" }.split(',').map { it.trim().toInt() }
    val warmupS = args.getOrElse(1) { "2" }.toLong()
    val measureS = args.getOrElse(2) { "5" }.toLong()
    val phases =
        args
            .getOrElse(3) { "rt,spr,bulk" }
            .split(',')
            .map { it.trim() }
            .toSet()
    println(
        "# setup: pool.size=${System.getProperty("kotlinx.coroutines.scheduler.core.pool.size") ?: "default"} " +
            "cpus=${Runtime.getRuntime().availableProcessors()} backend=${assertQuicheBackend()}",
    )
    for (m in counts) runLoad(m, warmupS, measureS, phases)
    System.exit(0)
}

private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

private fun runLoad(
    m: Int,
    warmupS: Long,
    measureS: Long,
    phases: Set<String>,
) {
    val serverReady = CompletableDeferred<Int>()
    val stop = CompletableDeferred<Unit>()
    val server =
        thread(name = "load-server", isDaemon = true) {
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
            thread(name = "load-conn-$i", isDaemon = true) {
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
            // One at a time: the loop group's placement sorts loops by a load that concurrent connects change
            // under it ("Comparison method violates its general contract" at 1024 concurrent connects).
            runBlocking { ready.await() }
        }

    if ("rt" in phases) {
        latencyPhase("RT", m, clients, warmupS, measureS) { scope ->
            val stream = scope.openStream()
            val req = scope.bufferFactory.allocate(64)
            req.writeByte(ECHO_MARKER)
            repeat(63) { req.writeByte('r'.code.toByte()) }
            req.resetForRead()
            LatencyOp(
                op = {
                    req.position(0)
                    stream.writeFully(req, IO_TIMEOUT)
                    stream.readExactly(64)
                },
                close = {
                    stream.shutdownSend()
                    stream.close()
                    req.freeIfNeeded()
                },
            )
        }
    }
    if ("spr" in phases) {
        latencyPhase("SPR", m, clients, warmupS, measureS) { scope ->
            val req = scope.bufferFactory.allocate(64)
            req.writeByte(ECHO_MARKER)
            repeat(63) { req.writeByte('r'.code.toByte()) }
            req.resetForRead()
            LatencyOp(
                op = {
                    val stream = scope.openStream()
                    try {
                        req.position(0)
                        stream.writeFully(req, IO_TIMEOUT)
                        stream.shutdownSend()
                        stream.readToEnd()
                    } finally {
                        stream.close()
                    }
                },
                close = { req.freeIfNeeded() },
            )
        }
    }
    if ("bulk" in phases) bulkPhase(m, clients, warmupS, measureS)
    stop.complete(Unit)
    server.join(10_000)
}

private class LatencyOp(
    val op: suspend () -> Unit,
    val close: suspend () -> Unit,
)

private fun latencyPhase(
    label: String,
    m: Int,
    clients: List<QuicScope>,
    warmupS: Long,
    measureS: Long,
    setup: suspend (QuicScope) -> LatencyOp,
) {
    val phase = Phase(warmupS, measureS)
    val lat = Array(m) { LongArray(2_000_000) }
    val counts = IntArray(m)
    val workers =
        clients.mapIndexed { i, scope ->
            thread(name = "load-app-$i") {
                runBlocking {
                    val op = setup(scope)
                    while (!phase.done()) {
                        val t0 = System.nanoTime()
                        op.op()
                        val dt = System.nanoTime() - t0
                        if (phase.measuring() && counts[i] < lat[i].size) lat[i][counts[i]++] = dt
                    }
                    op.close()
                }
            }
        }
    phase.start()
    Thread.sleep((warmupS + measureS / 2) * 1000)
    val census = threadCensus()
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
        "%-4s M=%3d  agg %9.0f op/s  per-conn p50 med %8.1f us  p99 med %8.1f us  p99 worst %8.1f us  max %9.1f us  cpu %5.2f cores  [%s]"
            .format(
                label,
                m,
                total / phase.seconds(),
                p50s.getOrElse(p50s.size / 2) { Double.NaN },
                p99s.getOrElse(p99s.size / 2) { Double.NaN },
                p99s.lastOrNull() ?: Double.NaN,
                max / 1000.0,
                phase.cores(),
                census,
            ),
    )
}

private fun bulkPhase(
    m: Int,
    clients: List<QuicScope>,
    warmupS: Long,
    measureS: Long,
) {
    val phase = Phase(warmupS, measureS)
    val bytes = LongArray(m)
    val workers =
        clients.mapIndexed { i, scope ->
            thread(name = "load-bulk-$i") {
                runBlocking {
                    val chunk = scope.bufferFactory.allocate(CHUNK)
                    repeat(CHUNK) { chunk.writeByte('D'.code.toByte()) }
                    while (!phase.done()) {
                        val stream = scope.openStream()
                        try {
                            repeat(BULK_BYTES / CHUNK) {
                                chunk.position(0)
                                chunk.setLimit(CHUNK)
                                stream.writeFully(chunk, IO_TIMEOUT)
                            }
                            stream.shutdownSend()
                            stream.readToEnd()
                        } finally {
                            stream.close()
                        }
                        if (phase.measuring()) bytes[i] += BULK_BYTES.toLong()
                    }
                    chunk.freeIfNeeded()
                }
            }
        }
    phase.start()
    workers.forEach { it.join() }
    println("BULK M=%3d  agg %8.1f MiB/s  cpu %5.2f cores".format(m, bytes.sum() / 1048576.0 / phase.seconds(), phase.cores()))
}

private const val CHUNK = 64 * 1024
private const val BULK_BYTES = 4 * 1024 * 1024

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

/** Live threads by kind, excluding this program's own application and connection-holder threads. */
private fun threadCensus(): String {
    val kinds = java.util.TreeMap<String, Int>()
    for (t in Thread.getAllStackTraces().keys) {
        val n = t.name
        val kind =
            when {
                n.startsWith("load-") -> continue
                n.startsWith("DefaultDispatcher-worker") -> "kotlinx-pool"
                n.startsWith("quic-loop") -> "quic-loop"
                n.startsWith("quic-deadline-timer") -> "deadline-timer"
                n.startsWith("kotlinx.coroutines.DefaultExecutor") -> "kotlinx-DefaultExecutor"
                else -> "other"
            }
        kinds[kind] = (kinds[kind] ?: 0) + 1
    }
    return kinds.entries.joinToString(" ") { "${it.key}=${it.value}" }
}
