package com.ditchoom.socket.benchmark.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.ServerSocket
import com.ditchoom.socket.allocate
import com.ditchoom.socket.connect
import com.ditchoom.socket.quic.FlowControl
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * An application's first requests, then its steady state:
 *
 *  1. A loopback TCP server and one connection: the first 64-byte round trip.
 *  2. A loopback QUIC server: the handshake and the first 64-byte round trip on a stream.
 *  3. reportFullyDrawn(): time-to-full-display is therefore cold start through the first TCP and QUIC
 *     exchanges, which is what FirstRequestBenchmark compares with and without the baseline profile.
 *  4. [ROUNDS] more round trips on each (TCP 64 B, QUIC 64 B and 1 KiB on the stream, QUIC
 *     stream-per-request), so the profile generator also records the steady-state code. Then "done".
 */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "running" }
        setContentView(status)
        thread(name = "workload") {
            val result = runCatching { runBlocking(Dispatchers.IO) { workload() } }
            runOnUiThread {
                status.text = result.fold({ "done" }, { "failed: $it" })
                // Reached only if the workload failed before its first requests completed.
                if (!fullyDrawn) reportFullyDrawn()
            }
        }
    }

    @Volatile private var fullyDrawn = false

    private fun firstRequestsDone() {
        runOnUiThread {
            fullyDrawn = true
            reportFullyDrawn()
        }
    }

    private suspend fun workload() =
        coroutineScope {
            val tcpServer = ServerSocket.allocate()
            val accepted = tcpServer.bind(0, "127.0.0.1")
            val tcpEcho = launch { accepted.collect { peer -> launch { echo(peer) } } }
            val client = ClientSocket.connect(tcpServer.port(), "127.0.0.1")
            val tcpRequest = filled(BufferFactory.Default, 64, ECHO)
            try {
                roundTrip(client, tcpRequest)
                withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tlsConfig(), quicOptions = OPTIONS) {
                    val quicEcho = launch { connections { coroutineScope { streams().collect { launch { echo(it) } } } } }
                    try {
                        withQuicConnection("127.0.0.1", port, OPTIONS, timeout = 30.seconds) {
                            val stream = openStream()
                            val small = filled(bufferFactory, 64, ECHO)
                            val large = filled(bufferFactory, 1024, ECHO)
                            try {
                                roundTrip(stream, small)
                                firstRequestsDone()
                                repeat(ROUNDS) {
                                    roundTrip(client, tcpRequest)
                                    roundTrip(stream, small)
                                    roundTrip(stream, large)
                                    val s = openStream()
                                    try {
                                        small.position(0)
                                        s.writeFully(small, TIMEOUT)
                                        s.shutdownSend()
                                        drain(s, Int.MAX_VALUE)
                                    } finally {
                                        s.close()
                                    }
                                }
                            } finally {
                                small.freeIfNeeded()
                                large.freeIfNeeded()
                                stream.shutdownSend()
                                stream.close()
                            }
                        }
                    } finally {
                        quicEcho.cancel()
                    }
                }
            } finally {
                tcpRequest.freeIfNeeded()
                client.close()
                tcpServer.close()
                tcpEcho.cancel()
            }
        }

    private suspend fun echo(peer: ByteStream) {
        try {
            while (true) {
                when (val r = peer.read(TIMEOUT)) {
                    is ReadResult.Data ->
                        try {
                            peer.writeFully(r.buffer, TIMEOUT)
                        } finally {
                            r.buffer.freeIfNeeded()
                        }
                    ReadResult.End, ReadResult.Reset -> break
                }
            }
            (peer as? QuicByteStream)?.shutdownSend()
        } catch (_: Exception) {
            // The other side went away at the end of the workload.
        } finally {
            peer.close()
        }
    }

    private suspend fun roundTrip(
        stream: ByteStream,
        request: PlatformBuffer,
    ) {
        request.position(0)
        stream.writeFully(request, TIMEOUT)
        check(drain(stream, request.limit()) == request.limit()) { "short echo" }
    }

    private suspend fun drain(
        source: ByteStream,
        expected: Int,
    ): Int {
        var got = 0
        while (got < expected) {
            when (val r = source.read(TIMEOUT)) {
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

    private fun filled(
        factory: BufferFactory,
        size: Int,
        first: Byte,
    ): PlatformBuffer {
        val buffer = factory.allocate(size)
        buffer.writeByte(first)
        repeat(size - 1) { buffer.writeByte('r'.code.toByte()) }
        buffer.resetForRead()
        return buffer
    }

    private fun tlsConfig(): QuicTlsConfig {
        fun extract(name: String): String {
            val out = File(cacheDir, name)
            val input = javaClass.classLoader!!.getResourceAsStream("certs/$name") ?: error("certs/$name missing")
            input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
            return out.absolutePath
        }
        return QuicTlsConfig(certChainPath = extract("cert.crt"), privKeyPath = extract("cert.key"))
    }

    private companion object {
        const val ROUNDS = 50
        const val ECHO: Byte = 'E'.code.toByte()
        val TIMEOUT = 10.seconds
        val OPTIONS =
            QuicOptions(
                alpnProtocols = listOf("bench"),
                verifyPeer = false,
                idleTimeout = 30.seconds,
                migration = MigrationPolicy.Forbidden,
                flowControl = FlowControl(initialMaxStreamsBidi = 1_000),
            )
    }
}
