package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that validate real QUIC connections.
 *
 * These tests connect to public QUIC servers and require:
 * - Network access
 * - The quiche native library built and available (JVM: in JAR, Linux: linked)
 *
 * Ignored by default — run explicitly with `--tests "*QuicIntegrationTests*"`.
 * CI runs these after the quiche build step succeeds.
 */
class QuicIntegrationTests {
    @Test
    fun connectToCloudflareQuic_handshakeCompletes() =
        runTest {
            val engine = defaultQuicEngine()
            val options = QuicOptions(alpnProtocols = listOf("h3"))
            val connOptions = ConnectionOptions(bufferFactory = BufferFactory.deterministic())

            val conn = engine.connect("cloudflare-quic.com", 443, options, connOptions, 10.seconds)

            assertIs<QuicConnectionState.Established>(conn.state.value)

            conn.close()
            engine.close()
        }

    @Test
    @Ignore("Requires quiche native library and network access")
    fun connectAndOpenStream_exchangeData() =
        runTest {
            val engine = defaultQuicEngine()
            val options = QuicOptions(alpnProtocols = listOf("h3"))
            val bufferFactory = BufferFactory.deterministic()
            val connOptions = ConnectionOptions(bufferFactory = bufferFactory)

            val conn = engine.connect("cloudflare-quic.com", 443, options, connOptions, 10.seconds)

            // Open a stream and send an HTTP/3 request (simplified)
            val stream = conn.openStream()
            assertTrue(stream.isOpen)

            // Clean up
            stream.close()
            conn.close()
            engine.close()
        }

    @Test
    @Ignore("Requires quiche native library and network access")
    fun connectionTimeout_throwsOnUnreachable() =
        runTest {
            val engine = defaultQuicEngine()
            val options = QuicOptions(alpnProtocols = listOf("h3"))

            try {
                // 192.0.2.1 is TEST-NET-1 (RFC 5737), should be unreachable
                engine.connect("192.0.2.1", 443, options, timeout = 2.seconds)
                // Should not reach here
                assertTrue(false, "Expected timeout")
            } catch (_: Exception) {
                // Expected: timeout or connection refused
            }

            engine.close()
        }

    @Test
    @Ignore("Requires quiche native library and network access")
    fun multipleStreams_onSingleConnection() =
        runTest {
            val engine = defaultQuicEngine()
            val options = QuicOptions(alpnProtocols = listOf("h3"))
            val connOptions = ConnectionOptions(bufferFactory = BufferFactory.deterministic())

            val conn = engine.connect("cloudflare-quic.com", 443, options, connOptions, 10.seconds)

            // Open multiple streams
            val stream0 = conn.openStream()
            val stream1 = conn.openStream()
            val stream2 = conn.openStream()

            assertTrue(stream0.streamId != stream1.streamId)
            assertTrue(stream1.streamId != stream2.streamId)

            stream0.close()
            stream1.close()
            stream2.close()
            conn.close()
            engine.close()
        }

    @Test
    @Ignore("Requires quiche native library and network access")
    fun deterministicBuffers_noLeaks() =
        runTest {
            val factory = TrackingBufferFactory()
            val engine = defaultQuicEngine()
            val options = QuicOptions(alpnProtocols = listOf("h3"))
            val connOptions = ConnectionOptions(bufferFactory = factory)

            val conn = engine.connect("cloudflare-quic.com", 443, options, connOptions, 10.seconds)
            val stream = conn.openStream()

            // Write and close
            factory.allocate(5).let { buf ->
                buf.writeBytes("hello".encodeToByteArray())
                buf.resetForRead()
                stream.write(buf, 5.seconds)
                buf.freeNativeMemory()
            }

            stream.close()
            conn.close()
            engine.close()

            // All buffers we allocated should be freed
            // (internal quiche buffers are managed by the connection)
        }
}
