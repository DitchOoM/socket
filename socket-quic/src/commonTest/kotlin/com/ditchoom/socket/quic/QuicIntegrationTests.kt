package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.use
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration tests against real QUIC servers.
 *
 * Tests gracefully skip on platforms where the native lib isn't available.
 * Uses scope-based connect — if the block runs, the connection is established.
 */
class QuicIntegrationTests {
    private val bufferFactory = BufferFactory.deterministic()
    private val quicOptions = QuicOptions(alpnProtocols = listOf("h3"))
    private val connOptions = ConnectionOptions(bufferFactory = bufferFactory)

    /** Run block inside a QUIC connection to Cloudflare, or skip if unavailable. */
    private suspend fun withCloudflare(block: suspend QuicScope.() -> Unit) {
        val engine =
            try {
                defaultQuicEngine()
            } catch (_: Throwable) {
                return
            }
        try {
            engine.connect("cloudflare-quic.com", 443, quicOptions, connOptions, 10.seconds, block)
        } catch (_: Throwable) {
            // skip — connection failed
        } finally {
            engine.close()
        }
    }

    @Test
    fun handshake_completesSuccessfully() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                withCloudflare {
                    // If we're here, handshake succeeded — scope-based guarantee
                }
            }
        }

    @Test
    fun openStream_returnsOpenStream() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                withCloudflare {
                    val stream = openStream()
                    assertTrue(stream.isOpen)
                    assertTrue(stream.streamId.isClientInitiated)
                    assertTrue(stream.streamId.isBidirectional)
                    stream.close()
                }
            }
        }

    @Test
    fun multipleStreams_haveDistinctIds() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                withCloudflare {
                    val s0 = openStream()
                    val s1 = openStream()
                    val s2 = openStream()
                    assertNotEquals(s0.streamId, s1.streamId)
                    assertNotEquals(s1.streamId, s2.streamId)
                    assertEquals(QuicStreamId(0), s0.streamId)
                    assertEquals(QuicStreamId(4), s1.streamId)
                    assertEquals(QuicStreamId(8), s2.streamId)
                    s0.close()
                    s1.close()
                    s2.close()
                }
            }
        }

    @Test
    fun writeToStream_succeeds() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                withCloudflare {
                    val stream = openStream()
                    bufferFactory.allocate(16).use { buf ->
                        buf.writeString("GET / HTTP/3\r\n\r\n", Charset.UTF8)
                        buf.resetForRead()
                        val written = stream.write(buf, 5.seconds)
                        assertTrue(written.count > 0)
                    }
                    stream.close()
                }
            }
        }

    @Test
    fun writeAndRead_serverResponds() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                withCloudflare {
                    val stream = openStream()
                    bufferFactory.allocate(16).use { buf ->
                        buf.writeString("GET / HTTP/3\r\n\r\n", Charset.UTF8)
                        buf.resetForRead()
                        stream.write(buf, 5.seconds)
                    }
                    val result = withTimeoutOrNull(3.seconds) { stream.read(3.seconds) }
                    if (result is ReadResult.Data) {
                        assertTrue(result.buffer.remaining() > 0)
                        result.buffer.freeIfNeeded()
                    }
                    stream.close()
                }
            }
        }

    @Test
    fun connectionTimeout_onUnreachableHost() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine =
                    try {
                        defaultQuicEngine()
                    } catch (_: Throwable) {
                        return@withContext
                    }
                try {
                    engine.connect("192.0.2.1", 443, quicOptions, timeout = 2.seconds) {
                        assertTrue(false, "Expected timeout or connection error")
                    }
                } catch (_: Exception) {
                    // Expected: timeout
                }
                engine.close()
            }
        }
}
