package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.TimeUnit

/*
 * QUIC over loopback, both ends in this JVM. The QUIC benchmarks use JMH annotations directly (this
 * module is JVM-only) because SampleTime — the mode that reports p50/p99, not just a mean — has no
 * kotlinx-benchmark equivalent. One op = one complete exchange; each op runs in its own runBlocking
 * on the JMH thread, which costs about a microsecond against a round trip of hundreds.
 */

/**
 * One long-lived bidirectional stream: write [size] bytes, read until all [size] come back. This is
 * the steady-state request/response latency of an established connection: no stream setup, no FIN.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class QuicLongLivedStreamRoundTrip {
    @Param("64", "1024")
    var size: Int = 0

    private lateinit var loopback: QuicLoopback
    private lateinit var stream: QuicByteStream
    private lateinit var request: PlatformBuffer

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = QuicLoopback()
        stream = runBlocking { loopback.client.openStream() }
        request = loopback.client.bufferFactory.allocate(size)
        request.writeByte(ECHO_MARKER)
        repeat(size - 1) { request.writeByte('r'.code.toByte()) }
        request.resetForRead()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        runBlocking {
            stream.shutdownSend()
            stream.close()
        }
        request.freeIfNeeded()
        loopback.close()
    }

    @Benchmark
    fun roundTrip(): Int =
        runBlocking {
            request.position(0)
            stream.writeFully(request, IO_TIMEOUT)
            stream.readExactly(size)
        }
}

/**
 * A new stream per request: open, write 64 bytes, FIN, read the echo to the server's FIN, close. The
 * shape HTTP/3 requests take, and the shape quic-bench.yaml's `rr_p50_us` has always measured.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class QuicStreamPerRequest {
    private lateinit var loopback: QuicLoopback
    private lateinit var request: PlatformBuffer

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = QuicLoopback()
        request = loopback.client.bufferFactory.allocate(SIZE)
        request.writeByte(ECHO_MARKER)
        repeat(SIZE - 1) { request.writeByte('r'.code.toByte()) }
        request.resetForRead()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        request.freeIfNeeded()
        loopback.close()
    }

    @Benchmark
    fun requestResponse(): Int =
        runBlocking {
            val stream = loopback.client.openStream()
            try {
                request.position(0)
                stream.writeFully(request, IO_TIMEOUT)
                stream.shutdownSend()
                stream.readToEnd()
            } finally {
                stream.close()
            }
        }

    private companion object {
        const val SIZE = 64
    }
}

/**
 * Bulk transfer: one stream per op carries [BULK_MIB] MiB client to server in 64 KiB writes, then FIN,
 * and the op ends when the server's FIN (sent after it has read everything) arrives. Each MiB counts as
 * one JMH operation, so the score reads directly as MiB/s.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class QuicBulkThroughput {
    private lateinit var loopback: QuicLoopback
    private lateinit var chunk: PlatformBuffer

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = QuicLoopback()
        // Filled once and re-sent: a write leaves the bytes in place, so rewinding is all a resend needs
        // and the loop measures the transport, not the filling.
        chunk = loopback.client.bufferFactory.allocate(CHUNK)
        repeat(CHUNK) { chunk.writeByte('D'.code.toByte()) }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        chunk.freeIfNeeded()
        loopback.close()
    }

    @Benchmark
    @OperationsPerInvocation(BULK_MIB)
    fun mibPerSecond(): Int =
        runBlocking {
            val stream = loopback.client.openStream()
            try {
                repeat(BULK_MIB * (1024 * 1024 / CHUNK)) {
                    chunk.position(0)
                    chunk.setLimit(CHUNK)
                    stream.writeFully(chunk, IO_TIMEOUT)
                }
                stream.shutdownSend()
                stream.readToEnd()
            } finally {
                stream.close()
            }
        }

    private companion object {
        const val CHUNK = 64 * 1024
        const val BULK_MIB = 16
    }
}

/** Reads until [expected] bytes have arrived; returns the count (fewer only if the peer ended first). */
internal suspend fun QuicByteStream.readExactly(expected: Int): Int {
    var got = 0
    while (got < expected) {
        when (val r = read(IO_TIMEOUT)) {
            is ReadResult.Data ->
                try {
                    got += r.buffer.remaining()
                } finally {
                    r.buffer.freeIfNeeded()
                }
            ReadResult.End, ReadResult.Reset -> return got
        }
    }
    return got
}

/** Reads until the peer's FIN; returns how many bytes arrived before it. */
internal suspend fun QuicByteStream.readToEnd(): Int {
    var got = 0
    while (true) {
        when (val r = read(IO_TIMEOUT)) {
            is ReadResult.Data ->
                try {
                    got += r.buffer.remaining()
                } finally {
                    r.buffer.freeIfNeeded()
                }
            ReadResult.End, ReadResult.Reset -> return got
        }
    }
}
