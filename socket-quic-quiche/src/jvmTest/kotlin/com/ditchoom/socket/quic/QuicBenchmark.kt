package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A QUIC loopback benchmark: one in-process server, one client, four workloads.
 *
 *  - `bulk_mbps`: one stream carries [BULK_BYTES] client → server, the server acknowledges with FIN.
 *  - `rr_p50_us` / `rr_p99_us`: [RR_ROUNDS] sequential 64-byte request/response round trips, one stream each.
 *  - `handshakes_per_s`: [HANDSHAKES] sequential connect + close.
 *  - `streams_mbps`: [PARALLEL_STREAMS] concurrent streams of [STREAM_BYTES] each.
 *
 * Usage: QuicBenchmarkKt <cert.crt> <cert.key> <out.json> [label]
 *
 * Run from the quic-echo fat jar (`java -cp quic-echo.jar com.ditchoom.socket.quic.QuicBenchmarkKt …`) by
 * .github/workflows/quic-bench.yaml, which also runs it under `tc netem` and compares against the last
 * main run. Each workload is warmed once, unmeasured. The numbers are for trend-spotting on one machine
 * shape, not for comparing across machines.
 */
private const val BULK_BYTES = 256L * 1024 * 1024
private const val RR_ROUNDS = 2_000
private const val HANDSHAKES = 200
private const val PARALLEL_STREAMS = 64
private const val STREAM_BYTES = 4L * 1024 * 1024
private const val CHUNK = 64 * 1024

/** withQuicConnection's timeout bounds the handshake AND the block; a full-scale workload outlives the 15 s default. */
private val BLOCK_TIMEOUT = 10.minutes

fun main(args: Array<String>) {
    require(args.size in 3..4) { "Usage: QuicBenchmark <cert.crt> <cert.key> <out.json> [label]" }
    val tls = QuicTlsConfig(certChainPath = args[0], privKeyPath = args[1])
    val out = File(args[2])
    val label = args.getOrElse(3) { "loopback" }
    val scale = (System.getenv("QUIC_BENCH_SCALE")?.toDoubleOrNull() ?: 1.0).coerceIn(0.01, 10.0)
    val options =
        QuicOptions(
            alpnProtocols = listOf("bench"),
            verifyPeer = false,
            idleTimeout = 60.seconds,
            migration = MigrationPolicy.Forbidden,
            flowControl =
                FlowControl(
                    initialMaxData = 64L * 1024 * 1024,
                    initialMaxStreamDataBidiLocal = 16L * 1024 * 1024,
                    initialMaxStreamDataBidiRemote = 16L * 1024 * 1024,
                    initialMaxStreamsBidi = 10_000,
                ),
        )
    val results =
        runBlocking(Dispatchers.IO) {
            withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tls, quicOptions = options) {
                val serverJob = launch { connections { sinkOrEcho() } }
                try {
                    val port = port
                    linkedMapOf(
                        "bulk_mbps" to bulk(port, options, (BULK_BYTES * scale).toLong()),
                        "streams_mbps" to parallelStreams(port, options, (STREAM_BYTES * scale).toLong()),
                    ) + requestResponse(port, options, (RR_ROUNDS * scale).toInt().coerceAtLeast(10)) +
                        ("handshakes_per_s" to handshakes(port, options, (HANDSHAKES * scale).toInt().coerceAtLeast(5)))
                } finally {
                    serverJob.cancel()
                }
            }
        }
    val json =
        buildString {
            append("{\"label\":\"").append(label).append("\",\"results\":{")
            append(results.entries.joinToString(",") { (k, v) -> "\"$k\":${"%.1f".format(v)}" })
            append("}}")
        }
    out.writeText(json + "\n")
    println(json)
    exitProcess(0)
}

/**
 * The server: a stream whose first byte is 'E' is echoed (request/response); anything else is drained
 * and answered with FIN alone once its FIN arrives (throughput).
 */
private suspend fun QuicScope.sinkOrEcho() {
    coroutineScope {
        streams().collect { stream ->
            launch {
                try {
                    var echo: Boolean? = null
                    while (true) {
                        when (val r = stream.read(60.seconds)) {
                            is ReadResult.Data ->
                                try {
                                    if (echo == null) echo = r.buffer.remaining() > 0 && r.buffer[r.buffer.position()] == 'E'.code.toByte()
                                    if (echo == true) stream.writeFully(r.buffer, 30.seconds)
                                } finally {
                                    r.buffer.freeIfNeeded()
                                }
                            ReadResult.End, ReadResult.Reset -> break
                        }
                    }
                    stream.shutdownSend()
                } catch (_: Exception) {
                } finally {
                    stream.close()
                }
            }
        }
    }
}

/** Send [bytes] on [stream], FIN, and wait for the server's FIN: the transfer is complete when it is acknowledged. */
private suspend fun QuicScope.push(
    stream: QuicByteStream,
    bytes: Long,
) {
    // Filled once and re-sent: a write is zero-copy and leaves the bytes in place, so rewinding is all a
    // resend needs, and the loop measures the transport rather than the filling.
    val buf = bufferFactory.allocate(CHUNK)
    try {
        repeat(CHUNK) { buf.writeByte('D'.code.toByte()) }
        var left = bytes
        while (left > 0) {
            val n = minOf(left, CHUNK.toLong()).toInt()
            buf.position(0)
            buf.setLimit(n)
            stream.writeFully(buf, 60.seconds)
            left -= n
        }
    } finally {
        buf.freeIfNeeded()
    }
    stream.shutdownSend()
    while (true) {
        when (val r = stream.read(60.seconds)) {
            is ReadResult.Data -> r.buffer.freeIfNeeded()
            ReadResult.End, ReadResult.Reset -> break
        }
    }
    stream.close()
}

private fun mbps(
    bytes: Long,
    elapsed: Duration,
): Double = bytes * 8.0 / 1_000_000.0 / (elapsed.inWholeMicroseconds / 1_000_000.0)

private suspend fun bulk(
    port: Int,
    options: QuicOptions,
    bytes: Long,
): Double =
    withQuicConnection("127.0.0.1", port, options, timeout = BLOCK_TIMEOUT) {
        push(openStream(), bytes / 8) // warm-up
        val start = TimeSource.Monotonic.markNow()
        push(openStream(), bytes)
        mbps(bytes, start.elapsedNow())
    }

private suspend fun parallelStreams(
    port: Int,
    options: QuicOptions,
    perStream: Long,
): Double =
    withQuicConnection("127.0.0.1", port, options, timeout = BLOCK_TIMEOUT) {
        push(openStream(), perStream) // warm-up
        val start = TimeSource.Monotonic.markNow()
        coroutineScope { (1..PARALLEL_STREAMS).map { async { push(openStream(), perStream) } }.awaitAll() }
        mbps(perStream * PARALLEL_STREAMS, start.elapsedNow())
    }

private suspend fun requestResponse(
    port: Int,
    options: QuicOptions,
    rounds: Int,
): Map<String, Double> =
    withQuicConnection("127.0.0.1", port, options, timeout = BLOCK_TIMEOUT) {
        suspend fun once(): Long {
            val start = TimeSource.Monotonic.markNow()
            val stream = openStream()
            val buf = bufferFactory.allocate(64)
            try {
                buf.writeByte('E'.code.toByte())
                repeat(63) { buf.writeByte('r'.code.toByte()) }
                buf.resetForRead()
                stream.writeFully(buf, 30.seconds)
            } finally {
                buf.freeIfNeeded()
            }
            stream.shutdownSend()
            var got = 0
            while (got < 64) {
                when (val r = stream.read(30.seconds)) {
                    is ReadResult.Data ->
                        try {
                            got += r.buffer.remaining()
                        } finally {
                            r.buffer.freeIfNeeded()
                        }
                    ReadResult.End, ReadResult.Reset -> break
                }
            }
            stream.close()
            return start.elapsedNow().inWholeMicroseconds
        }
        repeat(rounds / 10) { once() } // warm-up
        val samples = LongArray(rounds) { once() }.sorted()
        mapOf(
            "rr_p50_us" to samples[samples.size / 2].toDouble(),
            "rr_p99_us" to samples[(samples.size * 99) / 100].toDouble(),
        )
    }

private suspend fun handshakes(
    port: Int,
    options: QuicOptions,
    count: Int,
): Double {
    withQuicConnection("127.0.0.1", port, options, timeout = BLOCK_TIMEOUT) { } // warm-up
    val start = TimeSource.Monotonic.markNow()
    repeat(count) { withQuicConnection("127.0.0.1", port, options, timeout = BLOCK_TIMEOUT) { } }
    return count / (start.elapsedNow().inWholeMicroseconds / 1_000_000.0)
}
