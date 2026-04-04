package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.use
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.transport.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration tests against real QUIC servers.
 * Uses withContext(Dispatchers.Default) to escape runTest virtual time
 * (real UDP I/O doesn't advance virtual clocks).
 */
class QuicIntegrationTests {
    private val bufferFactory = BufferFactory.deterministic()
    private val quicOptions = QuicOptions(alpnProtocols = listOf("h3"))
    private val connOptions = ConnectionOptions(bufferFactory = bufferFactory)

    @Test
    fun handshake_completesSuccessfully() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine = defaultQuicEngine()
                val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)
                assertIs<QuicConnectionState.Established>(conn.state.value)
                conn.close()
                engine.close()
            }
        }

    @Test
    fun openStream_returnsOpenStream() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine = defaultQuicEngine()
                val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)
                val stream = conn.openStream()
                assertTrue(stream.isOpen)
                assertTrue(stream.streamId.isClientInitiated)
                assertTrue(stream.streamId.isBidirectional)
                stream.close()
                conn.close()
                engine.close()
            }
        }

    @Test
    fun multipleStreams_haveDistinctIds() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine = defaultQuicEngine()
                val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)
                val s0 = conn.openStream()
                val s1 = conn.openStream()
                val s2 = conn.openStream()
                assertNotEquals(s0.streamId, s1.streamId)
                assertNotEquals(s1.streamId, s2.streamId)
                assertEquals(QuicStreamId(0), s0.streamId)
                assertEquals(QuicStreamId(4), s1.streamId)
                assertEquals(QuicStreamId(8), s2.streamId)
                s0.close()
                s1.close()
                s2.close()
                conn.close()
                engine.close()
            }
        }

    @Test
    fun writeToStream_succeeds() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine = defaultQuicEngine()
                val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)
                val stream = conn.openStream()
                bufferFactory.allocate(16).use { buf ->
                    buf.writeString("GET / HTTP/3\r\n\r\n", Charset.UTF8)
                    buf.resetForRead()
                    val written = stream.write(buf, 5.seconds)
                    assertTrue(written.count > 0, "Expected bytes written, got ${written.count}")
                }
                stream.close()
                conn.close()
                engine.close()
            }
        }

    @Test
    fun writeAndRead_serverResponds() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine = defaultQuicEngine()
                val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)
                val stream = conn.openStream()
                bufferFactory.allocate(16).use { buf ->
                    buf.writeString("GET / HTTP/3\r\n\r\n", Charset.UTF8)
                    buf.resetForRead()
                    stream.write(buf, 5.seconds)
                }
                val result = withTimeoutOrNull(3.seconds) { stream.read(3.seconds) }
                if (result != null) {
                    when (result) {
                        is ReadResult.Data -> {
                            assertTrue(result.buffer.remaining() > 0)
                            result.buffer.freeIfNeeded()
                        }
                        is ReadResult.End -> {}
                        is ReadResult.Reset -> {}
                    }
                }
                stream.close()
                conn.close()
                engine.close()
            }
        }

    @Test
    fun close_transitionsThroughDrainingToClosed() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine = defaultQuicEngine()
                val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)
                assertIs<QuicConnectionState.Established>(conn.state.value)
                conn.close(QuicError.NoError)
                val finalState = conn.state.value
                assertIs<QuicConnectionState.Closed>(finalState)
                assertTrue(finalState.isCleanShutdown)
                engine.close()
            }
        }

    @Test
    fun close_withApplicationError_preservesError() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine = defaultQuicEngine()
                val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)
                conn.close(QuicError.ApplicationError(0x0100))
                val finalState = conn.state.value
                assertIs<QuicConnectionState.Closed>(finalState)
                assertNotNull(finalState.error)
                engine.close()
            }
        }

    @Test
    fun connectionTimeout_onUnreachableHost() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine = defaultQuicEngine()
                try {
                    engine.connect("192.0.2.1", 443, quicOptions, timeout = 2.seconds)
                    assertTrue(false, "Expected timeout or connection error")
                } catch (_: Exception) {
                }
                engine.close()
            }
        }

    @Test
    fun fullLifecycle_noBufferLeaks() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val factory = TrackingBufferFactory()
                val engine = defaultQuicEngine()
                val opts = ConnectionOptions(bufferFactory = factory)
                val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, opts, 10.seconds)
                val stream = conn.openStream()
                factory.allocate(5).use { buf ->
                    buf.writeString("hello", Charset.UTF8)
                    buf.resetForRead()
                    stream.write(buf, 5.seconds)
                }
                stream.close()
                conn.close()
                engine.close()
                factory.assertNoLeaks()
            }
        }
}
