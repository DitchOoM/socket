package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.MockClientToServerSocket
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Simple length-prefixed string codec for testing.
 * Wire format: [2-byte length (Short)] [UTF-8 string bytes]
 */
object TestStringCodec : Codec<String> {
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

    // BackPatch — wire size depends on string length; framework falls back to defaultBufferSize
    // and grows on overflow. This shape exercises the send() retry path.
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

class CodecConnectionTests {
    private val testOptions =
        ConnectionOptions(
            readTimeout = 5.seconds,
            writeTimeout = 5.seconds,
        )

    private fun createPairCodecConnections(): Pair<CodecConnection<String>, CodecConnection<String>> {
        val (aStream, bStream) = MemoryTransport.createPair()
        val a =
            CodecConnection(
                stream = aStream,
                codec = TestStringCodec,
                options = testOptions,
            )
        val b =
            CodecConnection(
                stream = bStream,
                codec = TestStringCodec,
                options = testOptions,
            )
        return a to b
    }

    // ── send() + receive() round-trip ──

    @Test
    fun sendAndReceiveRoundTrip() =
        runTest {
            val (client, server) = createPairCodecConnections()

            client.send("hello world")
            val received = server.receive().first()
            assertEquals("hello world", received)

            client.close()
            server.close()
        }

    @Test
    fun multipleMessagesInSequence() =
        runTest {
            val (client, server) = createPairCodecConnections()

            client.send("msg1")
            client.send("msg2")
            client.send("msg3")
            client.close()

            val messages = server.receive().toList()
            assertEquals(listOf("msg1", "msg2", "msg3"), messages)

            server.close()
        }

    @Test
    fun emptyStringMessage() =
        runTest {
            val (client, server) = createPairCodecConnections()

            client.send("")
            val received = server.receive().first()
            assertEquals("", received)

            client.close()
            server.close()
        }

    @Test
    fun largeMessage() =
        runTest {
            val (client, server) = createPairCodecConnections()

            val large = "x".repeat(10_000)
            client.send(large)
            val received = server.receive().first()
            assertEquals(large, received)

            client.close()
            server.close()
        }

    // ── receive() completes on stream end ──

    @Test
    fun receiveCompletesOnStreamEnd() =
        runTest {
            val (client, server) = createPairCodecConnections()

            client.close()
            val messages = server.receive().toList()
            assertEquals(emptyList(), messages)

            server.close()
        }

    @Test
    fun receiveCompletesAfterLastMessage() =
        runTest {
            val (client, server) = createPairCodecConnections()

            client.send("only-one")
            client.close()

            val messages = server.receive().toList()
            assertEquals(listOf("only-one"), messages)

            server.close()
        }

    // ── bidirectional ──

    @Test
    fun bidirectionalCodecCommunication() =
        runTest {
            val (a, b) = createPairCodecConnections()

            a.send("ping")
            val received = b.receive().first()
            assertEquals("ping", received)

            b.send("pong")
            val response = a.receive().first()
            assertEquals("pong", response)

            a.close()
            b.close()
        }

    // ── receive() throws on Reset ──

    @Test
    fun receiveThrowsOnStreamReset() =
        runTest {
            val mock = MockClientToServerSocket()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadError(SocketClosedException.ConnectionReset("peer reset"))

            val stream = TcpByteStream(mock)
            val codec =
                CodecConnection(
                    stream = stream,
                    codec = TestStringCodec,
                        options = testOptions,
                )

            assertFailsWith<SocketClosedException.ConnectionReset> {
                codec.receive().toList()
            }

            codec.close()
        }

    // ── error propagation through CodecConnection ──

    @Test
    fun receiveThrowsOnSocketTimeout() =
        runTest {
            val mock = MockClientToServerSocket()
            mock.open(80, 5.seconds, "test")
            mock.enqueueReadError(SocketTimeoutException("timed out"))

            val stream = TcpByteStream(mock)
            val codec =
                CodecConnection(
                    stream = stream,
                    codec = TestStringCodec,
                        options = testOptions,
                )

            assertFailsWith<SocketTimeoutException> {
                codec.receive().toList()
            }

            codec.close()
        }

    // ── preSeed() for protocol upgrades ──

    @Test
    fun preSeedFeedsLeftoverBytesIntoStreamProcessor() =
        runTest {
            val (clientStream, serverStream) = MemoryTransport.createPair()

            // Simulate: handshake parser read "msg1" bytes but they belong to the framing phase
            val preSeeded = BufferFactory.Default.allocate(32)
            TestStringCodec.encode(preSeeded, "pre-seeded", EncodeContext.Empty)
            preSeeded.resetForRead()

            val server =
                CodecConnection(
                    stream = serverStream,
                    codec = TestStringCodec,
                        options = testOptions,
                )
            server.preSeed(preSeeded)

            // Also send a normal message through the stream
            val client =
                CodecConnection(
                    stream = clientStream,
                    codec = TestStringCodec,
                        options = testOptions,
                )
            client.send("after-upgrade")
            client.close()

            // Server should see pre-seeded message first, then the streamed one
            val messages = server.receive().toList()
            assertEquals(listOf("pre-seeded", "after-upgrade"), messages)

            server.close()
        }

    // ── CodecConnection.connect() with pooled factory ──

    @Test
    fun connectCreatesOpenStreamWithPooledFactory() =
        runTest {
            val transport = MemoryTransport()
            val conn =
                CodecConnection.connect(
                    hostname = "localhost",
                    port = 8080,
                    codec = TestStringCodec,
                    transport = transport,
                    options = testOptions,
                )

            assertTrue(conn.stream.isOpen)
            conn.close()
        }

    // ── impossible state guards ──

    @Test
    fun sequentialReceiveIsAllowed() =
        runTest {
            val (clientStream, serverStream) = MemoryTransport.createPair()
            val client =
                CodecConnection(
                    stream = clientStream,
                    codec = TestStringCodec,
                        options = testOptions,
                )
            val server =
                CodecConnection(
                    stream = serverStream,
                    codec = TestStringCodec,
                        options = testOptions,
                )

            // First collection (simulates handshake)
            client.send("handshake")
            val ack = server.receive().first()
            assertEquals("handshake", ack)

            // Second collection (simulates streaming) — should work
            client.send("stream-msg")
            client.close()
            val messages = server.receive().toList()
            assertEquals(listOf("stream-msg"), messages)

            server.close()
        }

    @Test
    fun preSeedBetweenReceiveCollectionsIsAllowed() =
        runTest {
            val (clientStream, serverStream) = MemoryTransport.createPair()
            val server =
                CodecConnection(
                    stream = serverStream,
                    codec = TestStringCodec,
                        options = testOptions,
                )

            // preSeed before first receive — allowed
            val buf1 = BufferFactory.Default.allocate(32)
            TestStringCodec.encode(buf1, "seed1", EncodeContext.Empty)
            buf1.resetForRead()
            server.preSeed(buf1)

            val msg1 = server.receive().first()
            assertEquals("seed1", msg1)

            // preSeed after receive completes — also allowed
            val buf2 = BufferFactory.Default.allocate(32)
            TestStringCodec.encode(buf2, "seed2", EncodeContext.Empty)
            buf2.resetForRead()
            server.preSeed(buf2)

            val client =
                CodecConnection(
                    stream = clientStream,
                    codec = TestStringCodec,
                        options = testOptions,
                )
            client.close()

            val remaining = server.receive().toList()
            assertEquals(listOf("seed2"), remaining)

            server.close()
        }

    @Test
    fun sendAfterCloseThrows() =
        runTest {
            val (stream, _) = MemoryTransport.createPair()
            val conn =
                CodecConnection(
                    stream = stream,
                    codec = TestStringCodec,
                        options = testOptions,
                )

            conn.close()
            assertFailsWith<IllegalStateException> {
                conn.send("should fail")
            }
        }

    @Test
    fun preSeedAfterCloseThrows() =
        runTest {
            val (stream, _) = MemoryTransport.createPair()
            val conn =
                CodecConnection(
                    stream = stream,
                    codec = TestStringCodec,
                        options = testOptions,
                )

            conn.close()
            assertFailsWith<IllegalStateException> {
                conn.preSeed(BufferFactory.Default.allocate(1))
            }
        }

    @Test
    fun receiveAfterCloseThrows() =
        runTest {
            val (stream, _) = MemoryTransport.createPair()
            val conn =
                CodecConnection(
                    stream = stream,
                    codec = TestStringCodec,
                        options = testOptions,
                )

            conn.close()
            assertFailsWith<IllegalStateException> {
                conn.receive()
            }
        }

    @Test
    fun closeIsIdempotent() =
        runTest {
            val (stream, _) = MemoryTransport.createPair()
            val conn =
                CodecConnection(
                    stream = stream,
                    codec = TestStringCodec,
                        options = testOptions,
                )

            conn.close()
            conn.close() // should not throw
        }

    /**
     * Regression guard for the MQTT 32KB publish overflow: if a codec's wireSizeHint
     * under-sizes the encode buffer for a particular message (MQTT PUBLISH wireSizeHint
     * is fixed at the codec level but payload size is per-message and can be large),
     * send() must grow and retry rather than letting BufferOverflowException reach
     * the caller. This test pins that behaviour via TestStringCodec — its wireSizeHint
     * is 2, so a 40000-char string would need at least 40002 bytes, well past the
     * 8192 default. Before the fix this test threw BufferOverflowException; after
     * the fix the round-trip completes.
     */
    @Test
    fun sendGrowsBufferWhenMessageExceedsWireSizeHintAndDefault() =
        runTest {
            val (a, b) = createPairCodecConnections()
            try {
                // 40_000 bytes of payload + 2-byte length prefix = 40_002 bytes on the wire,
                // well beyond ConnectionOptions.defaultBufferSize = 8192 and codec.wireSizeHint = 2.
                val large = "x".repeat(40_000)
                a.send(large)
                val received = b.receive().first()
                assertEquals(large.length, received.length)
                assertEquals(large, received)
            } finally {
                a.close()
                b.close()
            }
        }
}
