package com.ditchoom.socket.benchmark.slice

import com.ditchoom.socket.benchmark.ECHO_MARKER
import com.ditchoom.socket.benchmark.assertQuicheBackend
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.loadQuicheApi
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.io.File
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/*
 * The run-to-completion slice (SliceDriver.kt) on the shapes of QuicStreamBenchmarks.kt and
 * NettyQuicBenchmarks.kt: the same payloads, flow-control limits, ECHO_MARKER protocol and JMH modes, and the
 * same caller (one runBlocking per operation on the JMH thread).
 *
 * placement = loop: the server's stream handler is a coroutine resumed on the loop thread (Netty's handler on
 * its event loop). placement = owned: the handler runs on threads the server owns (Netty's EventExecutorGroup,
 * and this library's draft "handlers on owned threads").
 */

private const val QUIC_V1 = 0x00000001
private const val CUBIC = 1

private fun cString(s: String): NativeBuf {
    val buf = NativeBuf(s.length + 1)
    s.forEachIndexed { i, ch -> buf.bb.put(i, ch.code.toByte()) }
    buf.bb.put(s.length, 0)
    return buf
}

/** The settings QuicLoopback.OPTIONS resolves to, applied by hand (the library's applicator is internal). */
private fun sliceConfig(
    api: QuicheApi,
    server: Boolean,
): QuicheConfig {
    val config = api.configNew(QUIC_V1)
    if (server) {
        val dir = System.getProperty("bench.certDir") ?: error("bench.certDir is not set")
        check(api.configLoadCertChainFromPemFile(config, cString(File(dir, "cert.crt").path).address) == 0)
        check(api.configLoadPrivKeyFromPemFile(config, cString(File(dir, "cert.key").path).address) == 0)
    }
    val alpn = NativeBuf(6)
    alpn.bb.put(0, 5)
    "bench".forEachIndexed { i, ch -> alpn.bb.put(1 + i, ch.code.toByte()) }
    api.configSetApplicationProtos(config, alpn.address, 6)
    api.configSetMaxIdleTimeout(config, 600_000)
    api.configSetMaxRecvUdpPayloadSize(config, 1350)
    api.configSetMaxSendUdpPayloadSize(config, 1350)
    api.configSetInitialMaxData(config, 64L * 1024 * 1024)
    api.configSetInitialMaxStreamDataBidiLocal(config, 16L * 1024 * 1024)
    api.configSetInitialMaxStreamDataBidiRemote(config, 16L * 1024 * 1024)
    api.configSetInitialMaxStreamDataUni(config, 1_048_576)
    api.configSetInitialMaxStreamsBidi(config, 1_000_000)
    api.configSetInitialMaxStreamsUni(config, 100)
    api.configSetDisableActiveMigration(config, true)
    api.configSetActiveConnectionIdLimit(config, 8)
    api.configVerifyPeer(config, false)
    api.configSetCcAlgorithm(config, CUBIC)
    api.configEnableHystart(config, true)
    api.configEnablePacing(config, true)
    api.configDiscoverPmtu(config, false)
    api.configGrease(config, true)
    return config
}

private fun udp(): DatagramChannel {
    val ch = DatagramChannel.open(java.net.StandardProtocolFamily.INET)
    ch.configureBlocking(false)
    ch.setOption(StandardSocketOptions.SO_RCVBUF, 2 * 1024 * 1024)
    ch.setOption(StandardSocketOptions.SO_SNDBUF, 2 * 1024 * 1024)
    ch.bind(InetSocketAddress("127.0.0.1", 0))
    return ch
}

/** The handler of QuicLoopback.serveStream: echo a stream that starts with ECHO_MARKER, drain any other. */
private suspend fun serveStream(stream: SliceStream) {
    var first = true
    var echo = false
    while (true) {
        when (val r = stream.read()) {
            is NativeBuf ->
                try {
                    if (first) {
                        first = false
                        echo = r.remaining > 0 && r.bb.get(r.position) == ECHO_MARKER
                    }
                    if (echo) stream.write(r)
                } finally {
                    stream.release(r)
                }
            SliceEnd -> break
        }
    }
    stream.shutdownSend()
}

/** One slice server on 127.0.0.1 and one client connection to it, each on a loop thread of its own. */
internal class SliceLoopback(
    placementName: String,
    holdAck: Boolean,
) : AutoCloseable {
    private val api = loadQuicheApi()
    private val handlerThreads: ThreadPoolExecutor?
    private val serverLoop: SliceLoop
    private val clientLoop: SliceLoop
    val connection: SliceConn

    init {
        val placement =
            when (placementName) {
                "loop" -> {
                    handlerThreads = null
                    HandlerPlacement.OnLoop
                }
                "owned" -> {
                    val kept = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
                    val n = AtomicInteger()
                    // The pool of the library's draft: a hand-off to the most recently parked thread.
                    val pool =
                        ThreadPoolExecutor(kept, 64, 10, TimeUnit.SECONDS, SynchronousQueue()) { r ->
                            Thread(r, "slice-handler-${n.incrementAndGet()}").also { it.isDaemon = true }
                        }
                    pool.prestartAllCoreThreads()
                    handlerThreads = pool
                    HandlerPlacement.Owned(pool.asCoroutineDispatcher())
                }
                else -> error("placement must be loop or owned, was $placementName")
            }
        val serverChannel = udp()
        serverLoop =
            SliceLoop("slice-server-loop", api, sliceConfig(api, server = true), serverChannel, true, placement, holdAck, ::serveStream)
        val clientChannel = udp()
        val serverAddress = serverChannel.localAddress as InetSocketAddress
        clientChannel.connect(serverAddress)
        clientLoop =
            SliceLoop("slice-client-loop", api, sliceConfig(api, server = false), clientChannel, false, HandlerPlacement.OnLoop, holdAck) {}
        serverLoop.start()
        clientLoop.start()
        connection = clientLoop.connect(serverAddress)
    }

    /** Per-operation counts over everything since the loops started, both endpoints together. */
    fun report(ops: Long): String {
        val s = serverLoop.counts
        val c = clientLoop.counts
        val o = ops.coerceAtLeast(1).toDouble()

        fun per(v: Long) = "%.2f".format(v / o)
        return "# slice counts per op over $ops ops: datagrams ${per(s.datagramsSent + c.datagramsSent)}, " +
            "conn_send ${per(s.connSendCalls + c.connSendCalls)}, conn_recv ${per(s.connRecvCalls + c.connRecvCalls)}, " +
            "loop sleeps server ${per(s.blockingSelects)} client ${per(c.blockingSelects)}, " +
            "resumes off the loop server ${per(s.resumesOffLoop)} client ${per(c.resumesOffLoop)}, " +
            "queued tasks server ${per(s.tasks)} client ${per(c.tasks)}, " +
            "held flushes ${per(s.heldFlushes + c.heldFlushes)}, send drops ${s.sendDrops + c.sendDrops}"
    }

    override fun close() {
        clientLoop.close()
        serverLoop.close()
        handlerThreads?.shutdownNow()
    }
}

private fun filled(size: Int): NativeBuf {
    val buf = NativeBuf(size)
    buf.bb.put(0, ECHO_MARKER)
    for (i in 1 until size) buf.bb.put(i, 'r'.code.toByte())
    buf.limit = size
    return buf
}

private suspend fun SliceStream.readExactly(expected: Int): Int {
    var got = 0
    while (got < expected) {
        when (val r = read()) {
            is NativeBuf -> {
                got += r.remaining
                release(r)
            }
            SliceEnd -> return got
        }
    }
    return got
}

private suspend fun SliceStream.readToEnd(): Int {
    var got = 0
    while (true) {
        when (val r = read()) {
            is NativeBuf -> {
                got += r.remaining
                release(r)
            }
            SliceEnd -> return got
        }
    }
}

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

    private lateinit var loopback: SliceLoopback
    private lateinit var stream: SliceStream
    private lateinit var request: NativeBuf
    private var ops = 0L

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = SliceLoopback(placement, hold)
        stream = loopback.connection.openStream()
        request = filled(size)
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

    private lateinit var loopback: SliceLoopback
    private lateinit var request: NativeBuf
    private var ops = 0L

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = SliceLoopback(placement, hold)
        request = filled(64)
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

    private lateinit var loopback: SliceLoopback
    private lateinit var chunk: NativeBuf
    private var ops = 0L

    @Setup(Level.Trial)
    fun setUp() {
        assertQuicheBackend()
        loopback = SliceLoopback(placement, hold)
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
