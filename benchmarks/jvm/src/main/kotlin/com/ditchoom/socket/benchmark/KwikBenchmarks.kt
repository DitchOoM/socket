package com.ditchoom.socket.benchmark

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
import tech.kwik.core.QuicClientConnection
import tech.kwik.core.QuicConnection
import tech.kwik.core.QuicStream
import tech.kwik.core.log.NullLogger
import tech.kwik.core.server.ApplicationProtocolConnection
import tech.kwik.core.server.ApplicationProtocolConnectionFactory
import tech.kwik.core.server.ServerConnectionConfig
import tech.kwik.core.server.ServerConnector
import java.io.File
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * Kwik (tech.kwik:kwik, a QUIC stack written in Java: no quiche, no native code) on the same loopback shapes as
 * QuicStreamBenchmarks.kt. Kwik's streams are java.io InputStream/OutputStream, so the client blocks the JMH
 * thread directly and the server serves each stream on a pooled platform thread (Kwik hands a stream to
 * acceptPeerInitiatedStream and expects the application to run its own thread). Flow-control limits are those
 * of QuicLoopback.OPTIONS where Kwik exposes them.
 */

private const val KWIK_ALPN = "bench"

private val STREAM_BUFFER = ThreadLocal.withInitial { ByteArray(64 * 1024) }

/** The server's per-stream handler: the same protocol as serveStream in QuicLoopback.kt. */
private fun serveKwikStream(stream: QuicStream) {
    try {
        val buf = STREAM_BUFFER.get()
        val input = stream.inputStream
        val output = stream.outputStream
        var echo: Boolean? = null
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val shouldEcho = echo ?: (n > 0 && buf[0] == ECHO_MARKER).also { echo = it }
            if (shouldEcho) {
                output.write(buf, 0, n)
                output.flush()
            }
        }
        output.close()
    } catch (_: Exception) {
        // The client went away (trial teardown); nothing to report.
    }
}

/** One Kwik server on 127.0.0.1 and one client connection to it, held open for the life of a trial. */
internal class KwikLoopback : AutoCloseable {
    private val handlers = Executors.newCachedThreadPool { r -> Thread(r, "kwik-stream").apply { isDaemon = true } }
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    private val server: ServerConnector
    val connection: QuicClientConnection

    init {
        val dir = System.getProperty("bench.certDir") ?: error("bench.certDir is not set: run through Gradle")
        val config =
            ServerConnectionConfig
                .builder()
                .maxIdleTimeoutInSeconds(600)
                .maxConnectionBufferSize(64L * 1024 * 1024)
                .maxBidirectionalStreamBufferSize(16L * 1024 * 1024)
                .maxOpenPeerInitiatedBidirectionalStreams(1000)
                .maxTotalPeerInitiatedBidirectionalStreams(1_000_000)
                .build()
        server =
            File(dir, "cert.crt").inputStream().use { cert ->
                File(dir, "cert.key").inputStream().use { key ->
                    ServerConnector
                        .builder()
                        .withSocket(socket)
                        .withCertificate(cert as InputStream, key as InputStream)
                        .withConfiguration(config)
                        .withLogger(NullLogger())
                        .build()
                }
            }
        server.registerApplicationProtocol(
            KWIK_ALPN,
            object : ApplicationProtocolConnectionFactory {
                override fun createConnection(
                    protocol: String,
                    connection: QuicConnection,
                ): ApplicationProtocolConnection =
                    object : ApplicationProtocolConnection {
                        override fun acceptPeerInitiatedStream(stream: QuicStream) {
                            handlers.execute { serveKwikStream(stream) }
                        }
                    }
            },
        )
        server.start()
        connection =
            QuicClientConnection
                .newBuilder()
                .uri(URI.create("quic://127.0.0.1:${socket.localPort}"))
                .applicationProtocol(KWIK_ALPN)
                .noServerCertificateCheck()
                .logger(NullLogger())
                .maxIdleTimeout(Duration.ofMinutes(10))
                .connectTimeout(Duration.ofSeconds(30))
                .defaultStreamReceiveBufferSize(16L * 1024 * 1024)
                .build()
        connection.connect()
    }

    override fun close() {
        runCatching { connection.closeAndWait() }
        runCatching { server.close() }
        handlers.shutdownNow()
        handlers.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/** Reads [expected] bytes from [input] (fewer only at the peer's FIN); returns the count. */
private fun InputStream.readExactly(
    expected: Int,
    into: ByteArray,
): Int {
    var got = 0
    while (got < expected) {
        val n = read(into, 0, minOf(into.size, expected - got))
        if (n < 0) break
        got += n
    }
    return got
}

/** Reads to the peer's FIN; returns the byte count. */
private fun InputStream.readToEnd(into: ByteArray): Int {
    var got = 0
    while (true) {
        val n = read(into)
        if (n < 0) return got
        got += n
    }
}

private fun echoRequest(size: Int): ByteArray = ByteArray(size) { if (it == 0) ECHO_MARKER else 'r'.code.toByte() }

/** [QuicLongLivedStreamRoundTrip] on Kwik. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class KwikLongLivedStreamRoundTrip {
    @Param("64", "1024")
    var size: Int = 0

    private lateinit var loopback: KwikLoopback
    private lateinit var stream: QuicStream
    private lateinit var request: ByteArray
    private lateinit var reply: ByteArray

    @Setup(Level.Trial)
    fun setUp() {
        loopback = KwikLoopback()
        stream = loopback.connection.createStream(true)
        request = echoRequest(size)
        reply = ByteArray(size)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        runCatching { stream.outputStream.close() }
        loopback.close()
    }

    @Benchmark
    fun roundTrip(): Int {
        stream.outputStream.write(request)
        stream.outputStream.flush()
        return stream.inputStream.readExactly(size, reply)
    }
}

/** [QuicStreamPerRequest] on Kwik: open, write 64 bytes, FIN, read the echo to the server's FIN. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class KwikStreamPerRequest {
    private lateinit var loopback: KwikLoopback
    private lateinit var request: ByteArray
    private lateinit var reply: ByteArray

    @Setup(Level.Trial)
    fun setUp() {
        loopback = KwikLoopback()
        request = echoRequest(SIZE)
        reply = ByteArray(SIZE * 4)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        loopback.close()
    }

    @Benchmark
    fun requestResponse(): Int {
        val stream = loopback.connection.createStream(true)
        stream.outputStream.write(request)
        stream.outputStream.close()
        return stream.inputStream.readToEnd(reply)
    }

    private companion object {
        const val SIZE = 64
    }
}

/** [QuicBulkThroughput] on Kwik: 16 MiB per op in 64 KiB writes, FIN, wait for the server's FIN; score = MiB/s. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class KwikBulkThroughput {
    private lateinit var loopback: KwikLoopback
    private lateinit var chunk: ByteArray
    private lateinit var reply: ByteArray

    @Setup(Level.Trial)
    fun setUp() {
        loopback = KwikLoopback()
        chunk = ByteArray(CHUNK) { 'D'.code.toByte() }
        reply = ByteArray(1024)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        loopback.close()
    }

    @Benchmark
    @OperationsPerInvocation(BULK_MIB)
    fun mibPerSecond(): Int {
        val stream = loopback.connection.createStream(true)
        repeat(BULK_MIB * (1024 * 1024 / CHUNK)) { stream.outputStream.write(chunk) }
        stream.outputStream.close()
        return stream.inputStream.readToEnd(reply)
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val BULK_MIB = 16
    }
}
