package com.ditchoom.socket.benchmark.slice

import com.ditchoom.socket.benchmark.assertQuicheBackend
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
 * The run-to-completion slice (SliceDriver.kt) on the shapes of QuicStreamBenchmarks.kt and
 * NettyQuicBenchmarks.kt: the same payloads, flow-control limits, ECHO_MARKER protocol and JMH modes, and the
 * same caller (one runBlocking per operation on the JMH thread).
 *
 * placement = loop: the server's stream handler is a coroutine resumed on the loop thread (Netty's handler on
 * its event loop). placement = owned: the handler runs on threads the server owns (Netty's EventExecutorGroup,
 * and this library's draft "handlers on owned threads"); ownedq is the same on a queue pool.
 * callerRuns = true: a thread that writes enters the connection itself instead of messaging the loop.
 */

/** QuicLongLivedStreamRoundTrip on the slice. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class SliceLongLivedStreamRoundTrip {
    @Param("64")
    var size: Int = 0

    @Param("loop", "owned")
    var placement: String = ""

    @Param("true")
    var hold: Boolean = true

    @Param("false")
    var callerRuns: Boolean = false

    private lateinit var loopback: SliceLoopback
    private lateinit var stream: SliceStream
    private lateinit var request: NativeBuf
    private var ops = 0L

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = SliceLoopback(placement, hold, callerRuns)
        stream = loopback.connection.openStream()
        request = filledRequest(size)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        println(loopback.report(ops))
        loopback.close()
    }

    @Benchmark
    fun roundTrip(): Int =
        runBlocking {
            ops++
            request.position = 0
            stream.write(request)
            stream.readExactly(size)
        }
}

/** QuicStreamPerRequest on the slice: open, write 64 bytes, FIN, read the echo to the server's FIN. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class SliceStreamPerRequest {
    @Param("loop", "owned")
    var placement: String = ""

    @Param("true")
    var hold: Boolean = true

    @Param("false")
    var callerRuns: Boolean = false

    private lateinit var loopback: SliceLoopback
    private lateinit var request: NativeBuf
    private var ops = 0L

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = SliceLoopback(placement, hold, callerRuns)
        request = filledRequest(64)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        println(loopback.report(ops))
        loopback.close()
    }

    @Benchmark
    fun requestResponse(): Int =
        runBlocking {
            ops++
            val stream = loopback.connection.openStream()
            request.position = 0
            stream.write(request)
            stream.shutdownSend()
            stream.readToEnd()
        }
}

/** QuicBulkThroughput on the slice: 16 MiB per op in 64 KiB writes, FIN, wait for the server's FIN; score = MiB/s. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class SliceBulkThroughput {
    @Param("loop", "owned")
    var placement: String = ""

    @Param("true")
    var hold: Boolean = true

    @Param("false")
    var callerRuns: Boolean = false

    private lateinit var loopback: SliceLoopback
    private lateinit var chunk: NativeBuf
    private var ops = 0L

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = SliceLoopback(placement, hold, callerRuns)
        chunk = NativeBuf(CHUNK)
        for (i in 0 until CHUNK) chunk.bb.put(i, 'D'.code.toByte())
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        println(loopback.report(ops * BULK_MIB))
        loopback.close()
    }

    @Benchmark
    @OperationsPerInvocation(BULK_MIB)
    fun mibPerSecond(): Int =
        runBlocking {
            ops++
            val stream = loopback.connection.openStream()
            repeat(BULK_MIB * (1024 * 1024 / CHUNK)) {
                chunk.position = 0
                chunk.limit = CHUNK
                stream.write(chunk)
            }
            stream.shutdownSend()
            stream.readToEnd()
        }

    private companion object {
        const val CHUNK = 64 * 1024
        const val BULK_MIB = 16
    }
}
