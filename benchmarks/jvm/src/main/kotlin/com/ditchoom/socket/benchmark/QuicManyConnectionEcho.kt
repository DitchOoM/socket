package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * N QUIC connections to one in-process server, each echoing 64 B on its own long-lived stream from its own
 * coroutine on [Dispatchers.Default]: the JVM twin of `NativeCompareBench`'s `qconns` workload
 * (benchmarks/native-compare), printing the same `RESULT` line. The warm-up starts once every connection has
 * its stream (`connect_s`).
 *
 * args: connections, warm-up seconds, measured seconds. The cert directory comes from `-Dbench.certDir`.
 * Exits non-zero when any connection fails, so a failed run never waits on its other connections.
 */
fun main(args: Array<String>) {
    val conns = args[0].toInt()
    val warmup = args[1].toLong().seconds
    val window = args[2].toLong().seconds
    val code =
        try {
            runBlocking(Dispatchers.Default) { manyConnections(conns, warmup, window) }
            0
        } catch (failure: Throwable) {
            failure.printStackTrace()
            1
        }
    exitProcess(code)
}

private suspend fun manyConnections(
    conns: Int,
    warmup: Duration,
    window: Duration,
): Unit =
    coroutineScope {
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = loopbackTls(), quicOptions = QuicLoopback.OPTIONS) {
            val serverJob = launch { connections { sinkOrEchoForLoad() } }
            val samples = Array(conns) { LongArray(1 shl 12) }
            val counts = IntArray(conns)
            val ready = List(conns) { CompletableDeferred<Unit>() }
            val go = CompletableDeferred<TimeSource.Monotonic.ValueTimeMark>()
            val connectStart = TimeSource.Monotonic.markNow()
            try {
                coroutineScope {
                    repeat(conns) { i ->
                        launch(Dispatchers.Default) {
                            withQuicConnection("127.0.0.1", port, QuicLoopback.OPTIONS, timeout = 24.hours) {
                                val stream = openStream()
                                val request = bufferFactory.allocate(SIZE)
                                request.writeByte(ECHO_MARKER)
                                repeat(SIZE - 1) { request.writeByte('r'.code.toByte()) }
                                request.resetForRead()
                                ready[i].complete(Unit)
                                val measureFrom = go.await() + warmup
                                val end = measureFrom + window
                                while (!end.hasPassedNow()) {
                                    val t = TimeSource.Monotonic.markNow()
                                    request.position(0)
                                    stream.writeFully(request, IO_TIMEOUT)
                                    stream.readExactly(SIZE)
                                    if (t > measureFrom && !end.hasPassedNow()) {
                                        if (counts[i] == samples[i].size) samples[i] = samples[i].copyOf(counts[i] * 2)
                                        samples[i][counts[i]++] = t.elapsedNow().inWholeNanoseconds
                                    }
                                }
                                stream.shutdownSend()
                                stream.close()
                                request.freeIfNeeded()
                            }
                        }
                    }
                    ready.awaitAll()
                    val connectSeconds = connectStart.elapsedNow().inWholeNanoseconds / 1e9
                    go.complete(TimeSource.Monotonic.markNow())
                    delay(warmup)
                    println("MEASURING")
                    val cpu0 = os.processCpuTime
                    delay(window)
                    val cpuUs = (os.processCpuTime - cpu0) / 1000
                    report(conns, samples, counts, window, cpuUs, connectSeconds)
                }
            } finally {
                serverJob.cancel()
            }
        }
    }

private fun report(
    conns: Int,
    samples: Array<LongArray>,
    counts: IntArray,
    window: Duration,
    cpuUs: Long,
    connectSeconds: Double,
) {
    val p50s = DoubleArray(conns)
    val p99s = DoubleArray(conns)
    for (i in 0 until conns) {
        val n = counts[i]
        samples[i].sort(0, n)
        p50s[i] = if (n == 0) Double.NaN else samples[i][n / 2] / 1000.0
        p99s[i] = if (n == 0) Double.NaN else samples[i][minOf(n - 1, (n * 0.99).toInt())] / 1000.0
    }
    p50s.sort()
    p99s.sort()
    val total = counts.sum()
    val secs = window.inWholeNanoseconds / 1e9
    println(
        "RESULT stack=jvm-ours workload=qconns$conns ops=$total seconds=$secs p50_us=${p50s[conns / 2]} " +
            "p99_us=${p99s[conns / 2]} p999_us=${p99s[conns - 1]} ops_per_s=${total / secs} mib_per_s=0.0 " +
            "cpu_us=$cpuUs maxrss_raw=0 idle_conns=${counts.count { it == 0 }} connect_s=$connectSeconds",
    )
}

private const val SIZE = 64
