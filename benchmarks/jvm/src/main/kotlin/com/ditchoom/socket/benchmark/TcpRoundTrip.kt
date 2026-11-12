package com.ditchoom.socket.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.allocate
import com.ditchoom.socket.connect
import com.ditchoom.socket.useAsyncChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * The same long-lived round trip as [QuicLongLivedStreamRoundTrip], over this library's TCP socket:
 * the reference that says how much of a QUIC round trip is QUIC (crypto, quiche, the driver loop)
 * and how much is the coroutine and socket plumbing both transports share.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class TcpRoundTrip {
    @Param("64", "1024")
    var size: Int = 0

    /** `nio2` (the default backend) or `nio` (the shared selector loops: `useAsyncChannels = false`). */
    @Param("nio2")
    var backend: String = "nio2"

    /** The deadline passed to every read and write: `30s` as this benchmark always did, or `infinite`, the default policy. */
    @Param("30s")
    var deadline: String = "30s"

    private var ioDeadline: Duration = IO_TIMEOUT

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var server: ServerSocket
    private lateinit var client: ClientToServerSocket
    private lateinit var request: PlatformBuffer

    @Setup(Level.Trial)
    fun setUp() {
        useAsyncChannels = backend == "nio2"
        ioDeadline = if (deadline == "infinite") Duration.INFINITE else IO_TIMEOUT
        runBlocking {
            server = ServerSocket.allocate()
            val accepted = server.bind(0, "127.0.0.1")
            serverScope.launch {
                accepted.collect { peer -> launch { echo(peer) } }
            }
            client = ClientSocket.connect(server.port(), "127.0.0.1")
        }
        request = BufferFactory.Default.allocate(size)
        repeat(size) { request.writeByte('r'.code.toByte()) }
        request.resetForRead()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        runBlocking {
            client.close()
            server.close()
        }
        serverScope.cancel()
        request.freeIfNeeded()
    }

    @Benchmark
    fun roundTrip(): Int =
        runBlocking {
            request.position(0)
            client.writeFully(request, ioDeadline)
            var got = 0
            while (got < size) {
                when (val r = client.read(ioDeadline)) {
                    is ReadResult.Data ->
                        try {
                            got += r.buffer.remaining()
                        } finally {
                            r.buffer.freeIfNeeded()
                        }
                    ReadResult.End, ReadResult.Reset -> break
                }
            }
            got
        }

    private suspend fun echo(peer: ClientSocket) {
        try {
            while (true) {
                when (val r = peer.read(if (ioDeadline.isInfinite()) ioDeadline else ioDeadline * 20)) {
                    is ReadResult.Data ->
                        try {
                            peer.writeFully(r.buffer, ioDeadline)
                        } finally {
                            r.buffer.freeIfNeeded()
                        }
                    ReadResult.End, ReadResult.Reset -> break
                }
            }
        } catch (_: Exception) {
            // Trial teardown closed the connection.
        } finally {
            peer.close()
        }
    }
}
