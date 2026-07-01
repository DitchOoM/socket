package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.MemoryTransport
import com.ditchoom.socket.transport.use
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [WebTransportTransport] / [WebTransportSessionTransport] against an injected fake
 * [WebTransportSupport] — no real HTTP/3, no browser. Verifies the single-stream projection
 * (RFC_UNIFIED_ESTABLISHMENT.md §3.3), URL construction from host:port + instance path (§4), and
 * the unified [com.ditchoom.socket.SocketException] error surface (§6.1).
 */
class WebTransportTransportTest {
    @Test
    fun connect_buildsUrlFromHostPortAndPath_andProjectsOneStream() =
        runTest {
            val support = FakeWebTransportSupport()
            val stream = WebTransportTransport(path = "/mqtt", support = support).connect("example.com", 443, TransportConfig())

            assertEquals("https://example.com:443/mqtt", support.lastUrl)

            val server = support.lastSession!!.peerStream!!
            BufferFactory.Default.allocate(2).also {
                it.writeString("hi", Charset.UTF8)
                it.resetForRead()
                stream.write(it)
            }
            val received = (server.read() as ReadResult.Data).buffer
            assertEquals("hi", received.readString(received.remaining(), Charset.UTF8))

            stream.close()
            assertTrue(support.lastSession!!.closed, "closing the projected stream must close the WebTransport session")
        }

    @Test
    fun connect_normalizesPathMissingLeadingSlash() =
        runTest {
            val support = FakeWebTransportSupport()
            WebTransportTransport(path = "chat", support = support).connect("h", 8443, TransportConfig()).close()
            assertEquals("https://h:8443/chat", support.lastUrl)
        }

    @Test
    fun connect_certFailure_mapsToSSLHandshakeFailed() =
        runTest {
            val support = FakeWebTransportSupport(failWith = WebTransportException("bad certificate: untrusted root"))
            assertFailsWith<SSLHandshakeFailedException> {
                WebTransportTransport(support = support).connect("h", 443, TransportConfig())
            }
        }

    @Test
    fun connect_genericFailure_mapsToSocketConnectionOther() =
        runTest {
            val support = FakeWebTransportSupport(failWith = WebTransportException("peer does not support WebTransport"))
            val e =
                assertFailsWith<SocketConnectionException.Other> {
                    WebTransportTransport(support = support).connect("h", 443, TransportConfig())
                }
            assertTrue(e.reason is com.ditchoom.socket.ConnectionFailureReason.Unknown)
        }

    @Test
    fun connect_streamReset_mapsToConnectionReset() =
        runTest {
            val support = FakeWebTransportSupport(streamAbortCode = 7u)
            val stream = WebTransportTransport(support = support).connect("h", 443, TransportConfig())
            val e =
                assertFailsWith<SocketClosedException.ConnectionReset> {
                    BufferFactory.Default
                        .allocate(1)
                        .also { it.resetForRead() }
                        .let { stream.write(it) }
                }
            assertTrue(e.cause is WebTransportStreamException)
        }

    @Test
    fun multiplexingTransport_withMux_opensTypedBidiStream_andClosesSession() =
        runTest {
            val support = FakeWebTransportSupport()
            val out =
                WebTransportTransport(path = "/m", support = support).withMux("h", 443, MuxStringCodec) {
                    // this: StreamMux<String> — the SAME agnostic surface QuicMultiplexingTransport exposes.
                    val conn = openBidirectional()
                    conn.send("ping")
                    "sent"
                }
            assertEquals("sent", out)
            // the framed message reached the peer side of the fake bidi stream
            val server = support.lastSession!!.peerStream!!
            val data = (server.read() as ReadResult.Data).buffer
            val len = data.readShort().toInt() and 0xFFFF
            assertEquals("ping", data.readString(len, Charset.UTF8))
            assertTrue(support.lastSession!!.closed, "withMux must close the WebTransport session")
        }

    @Test
    fun sessionTransport_use_closesSession() =
        runTest {
            val support = FakeWebTransportSupport()
            val out =
                WebTransportSessionTransport(path = "/s", support = support).use("h", 443) { session ->
                    session.openBidiStream()
                    "ok"
                }
            assertEquals("ok", out)
            assertTrue(support.lastSession!!.closed)
        }

    // --- fakes ---

    private class FakeWebTransportSupport(
        private val failWith: WebTransportException? = null,
        private val streamAbortCode: UInt? = null,
    ) : WebTransportSupport {
        var lastUrl: String? = null
        var lastSession: FakeWebTransportSession? = null

        override suspend fun connect(
            url: String,
            options: WebTransportOptions,
        ): WebTransportSession {
            lastUrl = url
            failWith?.let { throw it }
            return FakeWebTransportSession(streamAbortCode).also { lastSession = it }
        }
    }

    private class FakeWebTransportSession(
        private val streamAbortCode: UInt?,
    ) : WebTransportSession {
        override var isClosed = false
            private set
        override val closeInfo: WebTransportCloseInfo? get() = if (isClosed) WebTransportCloseInfo() else null
        var closed = false
        var peerStream: ByteStream? = null

        override suspend fun awaitClosed(): WebTransportCloseInfo = WebTransportCloseInfo()

        override suspend fun openBidiStream(): ByteStream {
            if (streamAbortCode != null) return AbortingByteStream(streamAbortCode)
            val (client, server) = MemoryTransport.createPair()
            peerStream = server
            return client
        }

        override suspend fun openUniStream(): ByteSink = throw UnsupportedOperationException("fake")

        override val incomingBidiStreams: Flow<ByteStream> = emptyFlow()
        override val incomingUniStreams: Flow<ByteSource> = emptyFlow()

        override suspend fun sendDatagram(payload: ReadBuffer) = throw UnsupportedOperationException("fake")

        override val datagrams: Flow<ReadBuffer> = emptyFlow()

        override suspend fun close(
            code: UInt,
            reason: String,
        ) {
            closed = true
            isClosed = true
        }
    }

    /** A bidi WebTransport stream whose write aborts with a peer reset. */
    private class AbortingByteStream(
        private val code: UInt,
    ) : ByteStream {
        override val isOpen = true
        override val readPolicy = com.ditchoom.buffer.flow.ReadPolicy.UntilClosed
        override val writePolicy =
            com.ditchoom.buffer.flow.WritePolicy
                .Bounded(kotlin.time.Duration.INFINITE)

        override suspend fun read(deadline: kotlin.time.Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: kotlin.time.Duration,
        ) = throw WebTransportStreamException(code, "peer reset")

        override suspend fun close() {}
    }
}

/** Length-prefixed UTF-8 string codec for the mux test (mirrors socket-quic's MuxStringCodec). */
private object MuxStringCodec : Codec<String> {
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
