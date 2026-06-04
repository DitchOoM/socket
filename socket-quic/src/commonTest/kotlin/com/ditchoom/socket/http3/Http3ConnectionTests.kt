package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Scripted-stream unit tests for [Http3Connection.bootstrap] (RFC 9114 §3.2/§6.2/§7.2.4): the
 * client's control/QPACK uni-stream setup and the peer-stream router that resolves SETTINGS.
 * Every test double's reads are finite, so the router completes on its own and the enclosing
 * [coroutineScope] joins it — no manual teardown. Live H3 is covered by a gated interop test.
 */
class Http3ConnectionTests {
    // --- bytes helpers ------------------------------------------------------

    private fun frameBytes(frame: Http3Frame): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        Http3FrameCodec.encode(buf, frame, EncodeContext.Empty)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    private fun clientSettings() =
        Http3Frame.Settings(
            listOf(
                Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 0L),
            ),
        )

    private fun dataChunk(bytes: List<Int>): ReadResult {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return ReadResult.Data(buf)
    }

    /** A peer control stream: type prefix 0x00, then [settings], then end-of-stream. */
    private fun peerControlStream(settings: Http3Frame.Settings): QuicByteStream =
        QuicByteStream(
            QuicStreamId(3), // server-initiated unidirectional
            RecordingByteStream(
                listOf(dataChunk(listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(settings)), ReadResult.End),
            ),
        )

    // --- test doubles -------------------------------------------------------

    /** A [ByteStream] that records everything written and replays a scripted read sequence. */
    private class RecordingByteStream(
        readScript: List<ReadResult> = emptyList(),
    ) : ByteStream {
        val written = mutableListOf<Int>()
        private val reads = ArrayDeque(readScript)
        var closed = false
            private set

        override val isOpen: Boolean get() = !closed

        override suspend fun read(timeout: Duration): ReadResult = if (reads.isEmpty()) ReadResult.End else reads.removeFirst()

        override suspend fun write(
            buffer: ReadBuffer,
            timeout: Duration,
        ): BytesWritten {
            val n = buffer.remaining()
            repeat(n) { written += buffer.readByte().toInt() and 0xFF }
            return BytesWritten(n)
        }

        override suspend fun close() {
            closed = true
        }
    }

    /**
     * A [QuicScope] test double: hands out [outgoing] uni streams in order from [openUniStream]
     * and replays [incoming] from [streams]. [openStream]/[acceptStream] are unused here, and
     * [migrate]/[pathState]/datagram methods keep the interface's no-op defaults.
     */
    private class FakeQuicScope(
        delegate: CoroutineScope,
        private val outgoing: ArrayDeque<QuicByteStream>,
        private val incoming: List<QuicByteStream>,
    ) : QuicScope,
        CoroutineScope by delegate {
        val remainingUniStreams get() = outgoing.size

        override suspend fun openUniStream(): QuicByteStream = outgoing.removeFirst()

        override suspend fun openStream(): QuicByteStream = throw UnsupportedOperationException()

        override suspend fun acceptStream(): QuicByteStream = throw UnsupportedOperationException()

        override fun streams(): Flow<QuicByteStream> = incoming.asFlow()
    }

    /** The three client uni streams [bootstrap] opens, with recording delegates exposed. */
    private class ClientStreams {
        val control = RecordingByteStream()
        val qpackEncoder = RecordingByteStream()
        val qpackDecoder = RecordingByteStream()

        fun outgoing(): ArrayDeque<QuicByteStream> =
            ArrayDeque(
                listOf(
                    QuicByteStream(QuicStreamId(2), control),
                    QuicByteStream(QuicStreamId(6), qpackEncoder),
                    QuicByteStream(QuicStreamId(10), qpackDecoder),
                ),
            )
    }

    // --- tests --------------------------------------------------------------

    @Test
    fun bootstrap_opensThreeUniStreams_andWritesControlPrefixAndSettings() =
        runTest {
            coroutineScope {
                val client = ClientStreams()
                val scope = FakeQuicScope(this, client.outgoing(), incoming = emptyList())

                Http3Connection.bootstrap(scope, ConnectionOptions())

                assertEquals(0, scope.remainingUniStreams, "bootstrap should open exactly three uni streams")
                assertEquals(
                    listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(clientSettings()),
                    client.control.written,
                    "control stream = type prefix 0x00 then the client SETTINGS frame",
                )
                assertEquals(listOf(Http3StreamType.QPACK_ENCODER.toInt()), client.qpackEncoder.written)
                assertEquals(listOf(Http3StreamType.QPACK_DECODER.toInt()), client.qpackDecoder.written)
            }
        }

    @Test
    fun peerSettings_resolvesFromPeerControlStream() =
        runTest {
            coroutineScope {
                val peerSettings =
                    Http3Frame.Settings(
                        listOf(
                            Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                            Http3Setting(Http3SettingId.MAX_FIELD_SECTION_SIZE, 16384L),
                            Http3Setting(Http3SettingId.ENABLE_CONNECT_PROTOCOL, 1L),
                        ),
                    )
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), listOf(peerControlStream(peerSettings)))

                val settings = Http3Connection.bootstrap(scope, ConnectionOptions()).peerSettings()

                assertEquals(0L, settings.qpackMaxTableCapacity)
                assertEquals(16384L, settings.maxFieldSectionSize)
                assertTrue(settings.enableConnectProtocol, "ENABLE_CONNECT_PROTOCOL=1 should parse as true")
            }
        }

    @Test
    fun peerSettings_resolvesWithQpackAndPushStreamsPresent() =
        runTest {
            coroutineScope {
                // Peer QPACK encoder (id 7) and a push stream (id 11) — drained, not parsed as frames.
                val peerQpackEnc =
                    QuicByteStream(
                        QuicStreamId(7),
                        RecordingByteStream(
                            listOf(dataChunk(listOf(Http3StreamType.QPACK_ENCODER.toInt(), 0x00, 0xFF)), ReadResult.End),
                        ),
                    )
                val peerPush =
                    QuicByteStream(
                        QuicStreamId(11),
                        RecordingByteStream(listOf(dataChunk(listOf(Http3StreamType.PUSH.toInt(), 0x01, 0x02)), ReadResult.End)),
                    )
                val scope =
                    FakeQuicScope(
                        this,
                        ClientStreams().outgoing(),
                        incoming = listOf(peerQpackEnc, peerPush, peerControlStream(clientSettings())),
                    )

                val settings = Http3Connection.bootstrap(scope, ConnectionOptions()).peerSettings()

                assertEquals(0L, settings.qpackBlockedStreams)
                assertTrue(peerQpackEnc.isOpen.not(), "peer QPACK stream should be drained then closed")
            }
        }

    @Test
    fun peerSettings_firstControlFrameNotSettings_throws() =
        runTest {
            coroutineScope {
                // Control stream whose first frame is DATA — a protocol violation.
                val data =
                    Http3Frame.Data(
                        BufferFactory.Default.allocate(1).also {
                            it.writeByte(0x41)
                            it.resetForRead()
                        },
                    )
                val controlBytes = listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(data)
                val peerControl =
                    QuicByteStream(QuicStreamId(3), RecordingByteStream(listOf(dataChunk(controlBytes), ReadResult.End)))
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControl))

                val connection = Http3Connection.bootstrap(scope, ConnectionOptions())
                assertFailsWith<Http3StreamException> { connection.peerSettings() }
            }
        }

    @Test
    fun peerSettings_connectionClosesBeforeSettings_throws() =
        runTest {
            coroutineScope {
                // No incoming streams → the streams flow completes before any SETTINGS arrive.
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = emptyList())
                val connection = Http3Connection.bootstrap(scope, ConnectionOptions())
                assertFailsWith<Http3StreamException> { connection.peerSettings() }
            }
        }

    @Test
    fun router_ignoresPeerBidirectionalStream() =
        runTest {
            coroutineScope {
                // id 1 = server-initiated bidirectional — closed, not parsed.
                val peerBidi = RecordingByteStream(listOf(dataChunk(listOf(0x00, 0x00)), ReadResult.End))
                val scope =
                    FakeQuicScope(
                        this,
                        ClientStreams().outgoing(),
                        incoming = listOf(QuicByteStream(QuicStreamId(1), peerBidi), peerControlStream(clientSettings())),
                    )

                Http3Connection.bootstrap(scope, ConnectionOptions()).peerSettings() // resolves despite the bidi stream

                assertTrue(peerBidi.closed, "a peer bidirectional stream should be closed, not parsed")
            }
        }
}
