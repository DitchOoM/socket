package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private object TestCodec : Codec<String> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): String {
        val length = buffer.readShort().toInt() and 0xFFFF
        return buffer.readString(length)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: String,
        context: EncodeContext,
    ) {
        val bytes = value.encodeToByteArray()
        buffer.writeShort(bytes.size.toShort())
        buffer.writeBytes(bytes)
    }

    override fun wireSize(
        value: String,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
        val length = stream.peekShort(baseOffset).toInt() and 0xFFFF
        return PeekResult.Complete(2 + length)
    }
}

class QuicStreamMuxTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    @Test
    fun bidirectionalStreamMuxExchange() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val muxResult = CompletableDeferred<String>()

                val opts = ConnectionOptions(readTimeout = 5.seconds, writeTimeout = 5.seconds)

                // Server: accept bidi stream via StreamMux, echo
                val serverDispatched = CompletableDeferred<Unit>()
                val serverJob =
                    launch(Dispatchers.IO) {
                        serverDispatched.complete(Unit)
                        server.connections {
                            val mux = QuicStreamMux(this, TestCodec, opts)
                            val conn = mux.acceptBidirectional()
                            val msg = conn.receive().first()
                            conn.send("echo: $msg")
                            conn.close()
                        }
                    }
                // Reactive wait for the launched serverJob to be dispatched and reach
                // server.connections() (which suspends on the accept channel). Without
                // this, on slower CI runners the client packet can arrive before the
                // server coroutine has even been scheduled, and quiche-vs-quiche
                // initial-packet pacing pushes the round-trip past the 10s budget below.
                serverDispatched.await()

                // Client: send via StreamMux
                val clientEngine =
                    try {
                        defaultQuicEngine()
                    } catch (_: Throwable) {
                        assumeTrue("Native lib not available", false)
                        return@withTimeout
                    }
                val clientJob =
                    launch(Dispatchers.IO) {
                        clientEngine.connectMux("localhost", server.port, testQuicOptions, TestCodec, connectionOptions = opts) {
                            val conn = openBidirectional()
                            assertTrue(conn.id >= 0)
                            conn.send("hello")
                            val response = conn.receive().first()
                            muxResult.complete(response)
                            conn.close()
                        }
                    }

                val result = withTimeout(10.seconds) { muxResult.await() }
                assertEquals("echo: hello", result)

                clientJob.cancel()
                serverJob.cancel()
                server.close()
                clientEngine.close()
            }
        }
}
