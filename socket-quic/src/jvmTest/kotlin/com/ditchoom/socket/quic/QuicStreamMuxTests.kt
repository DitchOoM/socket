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

                // [DIAGNOSTIC] Per-step logging: this test fails on CI with a 10s timeout
                // despite passing locally in ~108ms. The log markers below let us see
                // exactly which step stalls on the GH ubuntu-24.04 runner.
                val t0 = System.currentTimeMillis()

                fun ts(): String = "+${System.currentTimeMillis() - t0}ms"

                // Server: accept bidi stream via StreamMux, echo
                val serverDispatched = CompletableDeferred<Unit>()
                val serverJob =
                    launch(Dispatchers.IO) {
                        println("[mux ${ts()}] server: coroutine dispatched")
                        serverDispatched.complete(Unit)
                        server.connections {
                            println("[mux ${ts()}] server: connections handler invoked")
                            val mux = QuicStreamMux(this, TestCodec, opts)
                            println("[mux ${ts()}] server: awaiting acceptBidirectional()")
                            val conn = mux.acceptBidirectional()
                            println("[mux ${ts()}] server: accepted stream id=${conn.id}")
                            val msg = conn.receive().first()
                            println("[mux ${ts()}] server: received msg=$msg")
                            conn.send("echo: $msg")
                            println("[mux ${ts()}] server: sent echo")
                            conn.close()
                            println("[mux ${ts()}] server: closed conn")
                        }
                    }
                serverDispatched.await()
                println("[mux ${ts()}] test: serverDispatched.await() returned")

                // Client: send via StreamMux
                val clientEngine =
                    try {
                        defaultQuicEngine()
                    } catch (_: Throwable) {
                        assumeTrue("Native lib not available", false)
                        return@withTimeout
                    }
                println("[mux ${ts()}] test: launching client")
                val clientJob =
                    launch(Dispatchers.IO) {
                        println("[mux ${ts()}] client: coroutine dispatched, calling connectMux")
                        clientEngine.connectMux("localhost", server.port, testQuicOptions, TestCodec, connectionOptions = opts) {
                            println("[mux ${ts()}] client: connectMux block entered")
                            val conn = openBidirectional()
                            println("[mux ${ts()}] client: openBidirectional() returned id=${conn.id}")
                            assertTrue(conn.id >= 0)
                            conn.send("hello")
                            println("[mux ${ts()}] client: sent hello, awaiting response")
                            val response = conn.receive().first()
                            println("[mux ${ts()}] client: received response=$response")
                            muxResult.complete(response)
                            conn.close()
                            println("[mux ${ts()}] client: closed conn")
                        }
                        println("[mux ${ts()}] client: connectMux returned")
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
