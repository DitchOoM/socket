package com.ditchoom.socket.quic

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
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.MemoryTransport
import com.ditchoom.socket.transport.MultiplexingTransport
import com.ditchoom.socket.transport.Transport
import com.ditchoom.socket.transport.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [QuicTransport] / [QuicSessionTransport] against an injected fake [QuicEngine] —
 * no real QUIC, no libquiche. Verifies the single-stream projection (RFC_UNIFIED_ESTABLISHMENT.md
 * §3.3): one stream over a connection, closing the stream tears the connection down, and stream
 * aborts surface on the unified [SocketClosedException] family (RFC §6.1).
 */
class QuicTransportTest {
    private val opts = QuicOptions(alpnProtocols = listOf("test"))

    @Test
    fun connect_projectsOneStream_roundTrips() =
        runBlocking {
            val engine = FakeQuicEngine()
            val stream = QuicTransport(opts, engine).connect("example.com", 443, TransportConfig())

            val server = engine.connection!!.lastPeerStream!!
            // Client writes → server reads.
            BufferFactory.Default.allocate(5).also {
                it.writeString("hello", Charset.UTF8)
                it.resetForRead()
                stream.write(it)
            }
            val received = (server.read() as ReadResult.Data).buffer
            assertEquals("hello", received.readString(received.remaining(), Charset.UTF8))

            stream.close()
            assertTrue(engine.connection!!.closed, "closing the projected stream must close the QUIC connection")
        }

    @Test
    fun connect_streamReset_mapsToConnectionReset() =
        runBlocking {
            val engine = FakeQuicEngine(openStreamDelegate = ThrowingByteStream())
            val stream = QuicTransport(opts, engine).connect("example.com", 443, TransportConfig())

            val e =
                assertFailsWith<SocketClosedException.ConnectionReset> {
                    BufferFactory.Default
                        .allocate(1)
                        .also { it.resetForRead() }
                        .let { stream.write(it) }
                }
            assertTrue(e.cause is QuicStreamException, "must preserve the QuicStreamException as cause")
            stream.close()
        }

    @Test
    fun quicTransport_isBothSingleStreamAndMultiplexing() {
        val t = QuicTransport(opts, FakeQuicEngine())
        // Dual-SPI front door: a library holds a Transport and reaches mux by is-check, no stub.
        assertTrue(t is Transport)
        assertTrue(t is MultiplexingTransport)
    }

    @Test
    fun multiplexingTransport_withMux_opensTypedBidiStream_andClosesConnection() =
        runBlocking {
            val engine = FakeQuicEngine()
            val out =
                QuicTransport(opts, engine).withMux("h", 443, MuxStringCodec) {
                    // this: StreamMux<String> — agnostic multiplex surface, same code would run over WT.
                    val conn = openBidirectional()
                    conn.send("ping")
                    "sent"
                }
            assertEquals("sent", out)
            assertTrue(engine.connection!!.closed, "withMux must close the QUIC connection when the block ends")
        }

    @Test
    fun sessionTransport_use_closesConnection() =
        runBlocking {
            val engine = FakeQuicEngine()
            val opened =
                QuicSessionTransport(opts, engine).use("h", 443) { scope ->
                    scope.openStream()
                    "done"
                }
            assertEquals("done", opened)
            assertTrue(engine.connection!!.closed, "use{} must close the established connection")
        }

    // --- fakes ---

    private class FakeQuicEngine(
        private val openStreamDelegate: ByteStream? = null,
    ) : QuicEngine {
        var connection: FakeQuicConnection? = null
        override val capabilities = EngineCapabilities(supportsServer = false, supportsDatagrams = false, supportsMigration = false)

        override suspend fun connect(
            hostname: String,
            port: Int,
            quicOptions: QuicOptions,
            transport: TransportConfig,
            timeout: kotlin.time.Duration,
        ): QuicConnection = FakeQuicConnection(openStreamDelegate).also { connection = it }

        override suspend fun bind(
            port: Int,
            host: String?,
            tlsConfig: QuicTlsConfig,
            quicOptions: QuicOptions,
            timeout: kotlin.time.Duration,
        ): QuicServer = throw UnsupportedOperationException("fake")
    }

    private class FakeQuicConnection(
        private val openStreamDelegate: ByteStream?,
    ) : QuicConnection {
        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext = job + Dispatchers.Default
        override val bufferFactory: BufferFactory = BufferFactory.Default
        override val state: StateFlow<QuicConnectionState> = MutableStateFlow(QuicConnectionState.Established("test"))
        var closed = false
        var lastPeerStream: ByteStream? = null
        private var nextId = 0L

        override suspend fun openStream(): QuicByteStream {
            val id = QuicStreamId(nextId)
            nextId += 4
            val delegate =
                openStreamDelegate ?: run {
                    val (client, server) = MemoryTransport.createPair()
                    lastPeerStream = server
                    client
                }
            return QuicByteStream(id, delegate)
        }

        override suspend fun acceptStream(): QuicByteStream = throw UnsupportedOperationException("fake")

        override fun streams(): Flow<QuicByteStream> = emptyFlow()

        override suspend fun close(error: QuicError) {
            closed = true
            job.cancel()
        }
    }

    /** A ByteStream whose write always aborts with a peer stream reset. */
    private class ThrowingByteStream : ByteStream {
        override val isOpen = true
        override val readPolicy = ReadPolicy.UntilClosed
        override val writePolicy = WritePolicy.Bounded(kotlin.time.Duration.INFINITE)

        override suspend fun read(deadline: kotlin.time.Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: kotlin.time.Duration,
        ): BytesWritten = throw QuicStreamException(0, QuicStreamAbort.StopSending(42), "peer STOP_SENDING")

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
