package com.ditchoom.socket.benchmark.android

import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Load from this device to a remote `RemoteLoadServer` (socket-quic-quiche jvmTest). Runs only when the
 * `remoteHost` instrumentation argument is given; results go to logcat (tag RemoteQuicLoad) and to the
 * instrumentation output.
 *
 * Phases, each over M connections driven by a thread of their own:
 * - `rt`: 64-byte round trips on one long-lived stream;
 * - `spr`: stream per request (open, 64 bytes, FIN, read the echo to the server's FIN);
 * - `bulk`: 4 MiB uploads back to back;
 * - `idle`: connections held open with no traffic;
 * - `handshake`: sequential connections (handshake, one round trip, close). A connection slower than
 *   `captureSlowMs`, or one that fails, keeps its replay trace, qlog and TLS secrets under the app's
 *   external files directory (`Android/data/<package>/files/handshakes/`), named by run and index; the
 *   others are deleted.
 *
 * Arguments: remoteHost, remotePort, phases (default "rt,spr,bulk"), counts (default "1,64"), warmupS (3),
 * measureS (10), handshakes (100), handshakeTimeoutS (30), captureSlowMs (10000).
 */
@RunWith(AndroidJUnit4::class)
class RemoteQuicLoad {
    private val args = InstrumentationRegistry.getArguments()

    private fun arg(
        name: String,
        default: String,
    ): String = args.getString(name, default)

    @Test
    fun load() {
        val host = arg("remoteHost", "")
        assumeTrue("no remoteHost argument", host.isNotEmpty())
        assertJniBackend()
        val port = arg("remotePort", "0").toInt()
        val phases = arg("phases", "rt,spr,bulk").split(',').map { it.trim() }.toSet()
        if ("handshake" in phases) {
            handshakes(
                host,
                port,
                arg("handshakes", "100").toInt(),
                arg("handshakeTimeoutS", "30").toLong(),
                arg("captureSlowMs", "10000").toLong(),
            )
        }
        val counts = arg("counts", "1,64").split(',').map { it.trim().toInt() }
        val warmupS = arg("warmupS", "3").toLong()
        val measureS = arg("measureS", "10").toLong()
        if ((phases - "handshake").isNotEmpty()) {
            for (m in counts) runLoad(host, port, m, warmupS, measureS, phases)
        }
    }

    private fun runLoad(
        host: String,
        port: Int,
        m: Int,
        warmupS: Long,
        measureS: Long,
        phases: Set<String>,
    ) {
        val stop = CompletableDeferred<Unit>()
        val clients =
            List(m) { i ->
                val ready = CompletableDeferred<QuicScope>()
                thread(name = "load-conn-$i", isDaemon = true) {
                    try {
                        runBlocking(Dispatchers.IO) {
                            withQuicConnection(host, port, AndroidQuicLoopback.OPTIONS, timeout = 24.hours) {
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
        try {
            if ("rt" in phases) latencyPhase("RT", m, clients, warmupS, measureS, ::longLivedOp)
            if ("spr" in phases) latencyPhase("SPR", m, clients, warmupS, measureS, ::streamPerRequestOp)
            if ("bulk" in phases) bulkPhase(m, clients, warmupS, measureS)
            if ("idle" in phases) idlePhase(m, warmupS, measureS)
        } finally {
            stop.complete(Unit)
        }
    }

    /** Sequential connections, each: handshake, one 64-byte round trip, close. */
    private fun handshakes(
        host: String,
        port: Int,
        n: Int,
        timeoutS: Long,
        captureSlowMs: Long,
    ) {
        val dir = File(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null), "handshakes")
        dir.mkdirs()
        val runId = System.currentTimeMillis()
        val times = LongArray(n)
        var done = 0
        val failures = mutableMapOf<String, Int>()
        repeat(n) { i ->
            val capture = HandshakeCapture(dir, "hs-$runId-%04d".format(i))
            val t0 = System.nanoTime()
            val outcome =
                try {
                    runBlocking(Dispatchers.IO) {
                        withQuicConnection(host, port, capture.options(AndroidQuicLoopback.OPTIONS), timeout = timeoutS.seconds) {
                            val stream = openStream()
                            val req = request64()
                            try {
                                stream.writeFully(req, timeoutS.seconds)
                                stream.readExactly(64)
                            } finally {
                                stream.close()
                                req.freeIfNeeded()
                            }
                        }
                    }
                    HandshakeOutcome.Completed(System.nanoTime() - t0)
                } catch (t: Throwable) {
                    HandshakeOutcome.Failed(System.nanoTime() - t0, t.toString().take(120))
                }
            when (outcome) {
                is HandshakeOutcome.Completed -> times[done++] = outcome.nanos
                is HandshakeOutcome.Failed -> {
                    failures[outcome.cause] = failures.getOrDefault(outcome.cause, 0) + 1
                    report("HS failure #$i after ${outcome.nanos / 1_000_000} ms: ${outcome.cause}")
                }
            }
            if (outcome is HandshakeOutcome.Failed || outcome.nanos / 1_000_000 >= captureSlowMs) {
                capture.keep()
                report("HS kept #$i (${outcome.nanos / 1_000_000} ms) as ${capture.name}")
            } else {
                capture.discard()
            }
        }
        val ok = times.copyOf(done).also { it.sort() }
        report(
            "HS n=%d ok=%d failed=%d p50 %.1f ms p90 %.1f ms p99 %.1f ms max %.1f ms failures=%s"
                .format(
                    n,
                    done,
                    n - done,
                    percentileUs(ok, 0.50) / 1000.0,
                    percentileUs(ok, 0.90) / 1000.0,
                    percentileUs(ok, 0.99) / 1000.0,
                    maxOf(ok) / 1e6,
                    failures,
                ),
        )
    }

    private sealed interface HandshakeOutcome {
        val nanos: Long

        data class Completed(
            override val nanos: Long,
        ) : HandshakeOutcome

        data class Failed(
            override val nanos: Long,
            val cause: String,
        ) : HandshakeOutcome
    }

    /** One connection's replay trace (kept in memory until the outcome is known), qlog and TLS secrets. */
    private class HandshakeCapture(
        private val dir: File,
        val name: String,
    ) {
        private val lines = ConcurrentLinkedQueue<String>()
        private val qlog = File(dir, "$name.sqlog")
        private val keys = File(dir, "$name.keys")

        fun options(base: QuicOptions): QuicOptions =
            base.copy(
                trace =
                    QuicTraceCapture(
                        captureFor = {
                            QuicConnectionCapture(
                                sink = TraceSink { lines.add(it.toString()) },
                                qlog = QlogTarget.File(qlog.absolutePath),
                                trafficSecrets = TrafficSecretsLog.File(keys.absolutePath),
                            )
                        },
                    ),
            )

        fun keep() {
            File(dir, "$name.trace").writeText(lines.joinToString("\n", postfix = "\n"))
        }

        fun discard() {
            qlog.delete()
            keys.delete()
        }
    }

    private class LatencyOp(
        val op: suspend () -> Unit,
        val close: suspend () -> Unit,
    )

    private suspend fun longLivedOp(scope: QuicScope): LatencyOp {
        val stream = scope.openStream()
        val req = scope.request64()
        return LatencyOp(
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

    private suspend fun streamPerRequestOp(scope: QuicScope): LatencyOp {
        val req = scope.request64()
        return LatencyOp(
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

    private fun latencyPhase(
        label: String,
        m: Int,
        clients: List<QuicScope>,
        warmupS: Long,
        measureS: Long,
        setup: suspend (QuicScope) -> LatencyOp,
    ) {
        val phase = Phase(warmupS, measureS)
        val lat = Array(m) { LongArray(MAX_SAMPLES_PER_CONNECTION) }
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
        workers.forEach { it.join() }
        phase.awaitSamples()
        val all = LongArray(counts.sum())
        var at = 0
        for (i in 0 until m) {
            lat[i].copyInto(all, at, 0, counts[i])
            at += counts[i]
        }
        all.sort()
        val total = all.size
        report(
            "%-4s M=%3d ops=%d agg %9.0f op/s p50 %8.1f us p99 %8.1f us p99.9 %8.1f us max %9.1f us cpu %.0f us/op (%.2f cores)"
                .format(
                    label,
                    m,
                    total,
                    total / phase.seconds(),
                    percentileUs(all, 0.50),
                    percentileUs(all, 0.99),
                    percentileUs(all, 0.999),
                    maxOf(all) / 1000.0,
                    phase.cpuMs() * 1000.0 / total.coerceAtLeast(1),
                    phase.cores(),
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
        phase.awaitSamples()
        val mib = bytes.sum() / 1048576.0
        report(
            "BULK M=%3d agg %8.1f MiB/s cpu %.1f ms/MiB (%.2f cores)"
                .format(m, mib / phase.seconds(), phase.cpuMs() / mib.coerceAtLeast(1e-9), phase.cores()),
        )
    }

    private fun idlePhase(
        m: Int,
        warmupS: Long,
        measureS: Long,
    ) {
        Thread.sleep(warmupS * 1000)
        val c0 = Process.getElapsedCpuTime()
        Thread.sleep(measureS * 1000)
        val c1 = Process.getElapsedCpuTime()
        report("IDLE M=%3d cpu %.1f ms/s (%.3f cores)".format(m, (c1 - c0).toDouble() / measureS, (c1 - c0) / 1000.0 / measureS))
    }

    private fun QuicScope.request64(): PlatformBuffer {
        val buffer = bufferFactory.allocate(64)
        buffer.writeByte(ECHO_MARKER)
        repeat(63) { buffer.writeByte('r'.code.toByte()) }
        buffer.resetForRead()
        return buffer
    }

    private fun maxOf(sorted: LongArray): Long = if (sorted.isEmpty()) 0L else sorted.last()

    private fun percentileUs(
        sorted: LongArray,
        q: Double,
    ): Double = if (sorted.isEmpty()) Double.NaN else sorted[(sorted.size * q).toInt().coerceAtMost(sorted.size - 1)] / 1000.0

    private fun report(line: String) {
        Log.i(TAG, line)
        val bundle = Bundle()
        bundle.putString(android.app.Instrumentation.REPORT_KEY_STREAMRESULT, "$line\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
    }

    /**
     * Warm-up then a measured window, both judged from the start time; CPU is this process's CPU time
     * over the measured window, sampled by a timer thread at its two edges.
     */
    private class Phase(
        private val warmupS: Long,
        private val measureS: Long,
    ) {
        @Volatile private var startNs = Long.MAX_VALUE

        @Volatile private var cpuAtMeasure = 0L

        @Volatile private var cpuAtEnd = 0L

        private var sampler: Thread = Thread {}

        fun start() {
            startNs = System.nanoTime()
            sampler =
                thread(isDaemon = true) {
                    Thread.sleep(warmupS * 1000)
                    cpuAtMeasure = Process.getElapsedCpuTime()
                    Thread.sleep(measureS * 1000)
                    cpuAtEnd = Process.getElapsedCpuTime()
                }
        }

        /** Waits for the CPU sample at the end of the measured window. */
        fun awaitSamples() = sampler.join()

        private fun elapsedNs(): Long = System.nanoTime() - startNs

        fun measuring(): Boolean = elapsedNs() in warmupS * NANOS..<(warmupS + measureS) * NANOS

        fun done(): Boolean = elapsedNs() >= (warmupS + measureS) * NANOS

        fun seconds(): Double = measureS.toDouble()

        fun cpuMs(): Double = (cpuAtEnd - cpuAtMeasure).toDouble()

        fun cores(): Double = cpuMs() / 1000.0 / measureS
    }

    private companion object {
        const val TAG = "RemoteQuicLoad"
        const val CHUNK = 64 * 1024
        const val BULK_BYTES = 4 * 1024 * 1024
        const val MAX_SAMPLES_PER_CONNECTION = 50_000
        const val NANOS = 1_000_000_000L
    }
}
