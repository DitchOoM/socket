package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.use
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.transport.ReadResult
import kotlinx.coroutines.test.runTest
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
 * Validates the full zero-copy data path:
 *   BufferFactory → nativeMemoryAccess → quiche → UDP → TLS 1.3 → server
 *
 * Requires: quiche native library built (JVM: in JAR, Linux: linked).
 */
class QuicIntegrationTests {
    private val bufferFactory = BufferFactory.deterministic()
    private val quicOptions = QuicOptions(alpnProtocols = listOf("h3"))
    private val connOptions = ConnectionOptions(bufferFactory = bufferFactory)

    // --- Handshake ---

    @Test
    fun handshake_completesSuccessfully() =
        runTest {
            val engine = defaultQuicEngine()
            val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)

            assertIs<QuicConnectionState.Established>(conn.state.value)

            conn.close()
            engine.close()
        }

    // --- Stream lifecycle ---

    @Test
    fun openStream_returnsOpenStream() =
        runTest {
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

    @Test
    fun multipleStreams_haveDistinctIds() =
        runTest {
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

    // --- Stream data exchange ---

    @Test
    fun writeToStream_succeeds() =
        runTest {
            val engine = defaultQuicEngine()
            val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)

            val stream = conn.openStream()

            // Write data using buffer factory (zero-copy path)
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

    @Test
    fun writeAndRead_serverResponds() =
        runTest {
            val engine = defaultQuicEngine()
            val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)

            val stream = conn.openStream()

            // Write — server may respond with H3 data or reset (we're not speaking proper H3)
            bufferFactory.allocate(16).use { buf ->
                buf.writeString("GET / HTTP/3\r\n\r\n", Charset.UTF8)
                buf.resetForRead()
                stream.write(buf, 5.seconds)
            }

            // Try to read — expect either data, end, or reset (all valid responses)
            val result =
                withTimeoutOrNull(3.seconds) {
                    stream.read(3.seconds)
                }

            if (result != null) {
                when (result) {
                    is ReadResult.Data -> {
                        assertTrue(result.buffer.remaining() > 0, "Expected data in response")
                        result.buffer.freeIfNeeded()
                    }
                    is ReadResult.End -> {} // server closed cleanly
                    is ReadResult.Reset -> {} // server reset (expected for malformed H3)
                }
            }
            // null = timeout, also acceptable (server may not respond to invalid H3)

            stream.close()
            conn.close()
            engine.close()
        }

    // --- Connection close/drain ---

    @Test
    fun close_transitionsThroughDrainingToClosed() =
        runTest {
            val engine = defaultQuicEngine()
            val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)

            assertIs<QuicConnectionState.Established>(conn.state.value)

            conn.close(QuicError.NoError)

            val finalState = conn.state.value
            assertIs<QuicConnectionState.Closed>(finalState)
            assertTrue(finalState.isCleanShutdown)

            engine.close()
        }

    @Test
    fun close_withApplicationError_preservesError() =
        runTest {
            val engine = defaultQuicEngine()
            val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds)

            conn.close(QuicError.ApplicationError(0x0100)) // H3_NO_ERROR

            val finalState = conn.state.value
            assertIs<QuicConnectionState.Closed>(finalState)
            assertNotNull(finalState.error)

            engine.close()
        }

    // --- Error handling ---

    @Test
    fun connectionTimeout_onUnreachableHost() =
        runTest {
            val engine = defaultQuicEngine()

            try {
                // 192.0.2.1 is TEST-NET-1 (RFC 5737) — should be unreachable
                engine.connect("192.0.2.1", 443, quicOptions, timeout = 2.seconds)
                assertTrue(false, "Expected timeout or connection error")
            } catch (_: Exception) {
                // Expected: timeout, connection refused, or DNS failure
            }

            engine.close()
        }

    // --- Buffer leak detection ---

    @Test
    fun fullLifecycle_noBufferLeaks() =
        runTest {
            val factory = TrackingBufferFactory()
            val engine = defaultQuicEngine()
            val opts = ConnectionOptions(bufferFactory = factory)

            val conn = engine.connect("cloudflare-quic.com", 443, quicOptions, opts, 10.seconds)
            val stream = conn.openStream()

            // Write with use{} — auto-freed
            factory.allocate(5).use { buf ->
                buf.writeString("hello", Charset.UTF8)
                buf.resetForRead()
                stream.write(buf, 5.seconds)
            }

            stream.close()
            conn.close()
            engine.close()

            // Our buffers are freed. Internal quiche buffers (udpRecvBuf etc.)
            // are managed by JvmQuicConnection.close() which frees them all.
            factory.assertNoLeaks()
        }
}
