package com.ditchoom.socket.benchmark

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
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

/**
 * [TcpRoundTrip] on Ktor's raw sockets (io.ktor:ktor-network): the same long-lived echo of [size] bytes over
 * 127.0.0.1, each op in its own runBlocking on the JMH thread, the server on Dispatchers.IO. Ktor's channels
 * move ByteArrays, so the request here is one (a library the no-ByteArray rule of this repo does not bind).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class KtorTcpRoundTrip {
    @Param("64", "1024")
    var size: Int = 0

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var selector: SelectorManager
    private lateinit var server: ServerSocket
    private lateinit var client: Socket
    private lateinit var read: ByteReadChannel
    private lateinit var write: ByteWriteChannel
    private lateinit var request: ByteArray
    private lateinit var reply: ByteArray

    @Setup(Level.Trial)
    fun setUp() {
        selector = SelectorManager(Dispatchers.IO)
        runBlocking {
            val builder = aSocket(selector).tcp()
            server = builder.bind("127.0.0.1", 0)
            serverScope.launch {
                while (true) {
                    val peer = server.accept()
                    launch { echo(peer) }
                }
            }
            client = builder.connect(server.localAddress as InetSocketAddress) { noDelay = true }
            read = client.openReadChannel()
            write = client.openWriteChannel(autoFlush = true)
        }
        request = ByteArray(size) { 'r'.code.toByte() }
        reply = ByteArray(size)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        client.close()
        server.close()
        serverScope.cancel()
        selector.close()
    }

    @Benchmark
    fun roundTrip(): Int =
        runBlocking {
            write.writeFully(request)
            read.readFully(reply)
            size
        }

    private suspend fun echo(peer: Socket) {
        try {
            val input = peer.openReadChannel()
            val output = peer.openWriteChannel(autoFlush = true)
            val buf = ByteArray(8192)
            while (true) {
                val n = input.readAvailable(buf)
                if (n < 0) break
                output.writeFully(buf, 0, n)
            }
        } catch (_: Exception) {
            // Trial teardown closed the connection.
        } finally {
            peer.close()
        }
    }
}
