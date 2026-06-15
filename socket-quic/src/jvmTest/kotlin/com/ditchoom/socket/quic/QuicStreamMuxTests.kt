package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.TransportConfig
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

/**
 * **Lifecycle:** all server / client work runs inside [withQuicServer] /
 * [withQuicMux] block-takers — scope-only construction guarantees `close()`
 * on every exit path. The previous `assumeTrue(CI == null || RUN_FLAKY_TESTS)`
 * gate that hid this test on CI is dropped: it papered over a lifecycle gap
 * that is now closed by construction.
 */
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

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun bidirectionalStreamMuxExchange() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                // 30s whole-test budget: the client connect alone defaults to 15s, so the old 15s
                // cap was inconsistent with the work below (connect + send + receive) and a
                // slow-but-correct run could time out opaquely. (Same shape as the migration-test
                // de-flake.)
                withTimeout(30.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val opts = TransportConfig(readPolicy = ReadPolicy.Bounded(5.seconds), writePolicy = WritePolicy.Bounded(5.seconds))

                        // Server: accept bidi stream via StreamMux, echo.
                        val serverDispatched = CompletableDeferred<Unit>()
                        val serverJob =
                            launch(Dispatchers.IO) {
                                serverDispatched.complete(Unit)
                                connections {
                                    val mux = QuicStreamMux(this, TestCodec, opts)
                                    val conn = mux.acceptBidirectional()
                                    val msg = conn.receive().first()
                                    conn.send("echo: $msg")
                                    conn.close()
                                }
                            }
                        serverDispatched.await()

                        try {
                            // Run the client INLINE (not in a child launch funneling the result
                            // through an unbounded CompletableDeferred.await): a per-op withTimeout
                            // throws a CancellationException that would cancel a child coroutine
                            // silently, leaving the await to time out opaquely and masking the real
                            // cause. Inline, any failure propagates straight to the test.
                            val response =
                                withQuicMux("localhost", port, testQuicOptions, TestCodec, connectionOptions = opts) {
                                    val conn = openBidirectional()
                                    assertTrue(conn.id >= 0)
                                    conn.send("hello")
                                    val r = conn.receive().first()
                                    conn.close()
                                    r
                                }
                            assertEquals("echo: hello", response)
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
