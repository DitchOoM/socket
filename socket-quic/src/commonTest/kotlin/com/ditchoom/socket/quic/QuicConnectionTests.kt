package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class QuicConnectionTests {
    // --- openStream ---

    @Test
    fun openStream_returnsClientInitiatedBidiStream() =
        runTest {
            val conn = MockQuicConnection()
            val stream = conn.openStream()

            assertEquals(QuicStreamId(0), stream.streamId)
            assertTrue(stream.streamId.isClientInitiated)
            assertTrue(stream.streamId.isBidirectional)
        }

    @Test
    fun openStream_incrementsStreamIdBy4() =
        runTest {
            val conn = MockQuicConnection()
            val s0 = conn.openStream()
            val s1 = conn.openStream()
            val s2 = conn.openStream()

            assertEquals(QuicStreamId(0), s0.streamId)
            assertEquals(QuicStreamId(4), s1.streamId)
            assertEquals(QuicStreamId(8), s2.streamId)
        }

    @Test
    fun openStream_afterClose_throwsIllegalState() =
        runTest {
            val conn = MockQuicConnection()
            conn.close()

            assertFailsWith<IllegalStateException> {
                conn.openStream()
            }
        }

    @Test
    fun openStream_whenNotEstablished_throwsIllegalState() =
        runTest {
            val conn = MockQuicConnection(QuicConnectionState.Handshaking)

            assertFailsWith<IllegalStateException> {
                conn.openStream()
            }
        }

    // --- Stream data exchange via mock ---

    @Test
    fun openStream_canExchangeData() =
        runTest {
            val conn = MockQuicConnection()
            val stream = conn.openStream()
            val peerStream = conn.peerStreams[stream.streamId]!!

            // Client writes, server reads
            val buf = BufferFactory.Default.allocate(3)
            buf.writeBytes("hey".encodeToByteArray())
            buf.resetForRead()
            stream.write(buf, 5.seconds)

            val result = peerStream.read(5.seconds)
            assertIs<ReadResult.Data>(result)

            stream.close()
        }

    // --- Peer-initiated streams ---

    @Test
    fun acceptStream_receivesPeerInjectedStream() =
        runTest {
            val conn = MockQuicConnection()
            val peerId = QuicStreamId(1) // server-initiated bidi
            val (injected, _) = conn.injectPeerStream(peerId)

            val accepted = conn.acceptStream()
            assertEquals(peerId, accepted.streamId)
        }

    @Test
    fun acceptStream_afterClose_throwsIllegalState() =
        runTest {
            val conn = MockQuicConnection()
            conn.close()

            assertFailsWith<IllegalStateException> {
                conn.acceptStream()
            }
        }

    // --- State transitions ---

    @Test
    fun initialState_isEstablished() =
        runTest {
            val conn = MockQuicConnection()
            assertIs<QuicConnectionState.Established>(conn.state.value)
        }

    @Test
    fun close_transitionsToClosed() =
        runTest {
            val conn = MockQuicConnection()
            conn.close()
            assertIs<QuicConnectionState.Closed>(conn.state.value)
        }

    @Test
    fun close_withError_preservesError() =
        runTest {
            val conn = MockQuicConnection()
            conn.close(QuicError.ProtocolViolation)

            val state = conn.state.value
            assertIs<QuicConnectionState.Closed>(state)
            assertIs<QuicError.ProtocolViolation>(state.error)
        }

    @Test
    fun close_withNoError_isCleanShutdown() =
        runTest {
            val conn = MockQuicConnection()
            conn.close(QuicError.NoError)

            val state = conn.state.value
            assertIs<QuicConnectionState.Closed>(state)
            assertTrue(state.isCleanShutdown)
        }

    @Test
    fun close_isIdempotent() =
        runTest {
            val conn = MockQuicConnection()
            conn.close()
            conn.close() // should not throw
            assertIs<QuicConnectionState.Closed>(conn.state.value)
        }

    // --- State machine guards ---

    @Test
    fun transitionTo_idle() =
        runTest {
            val conn = MockQuicConnection(QuicConnectionState.Idle)
            assertIs<QuicConnectionState.Idle>(conn.state.value)
        }

    @Test
    fun transitionTo_handshaking() =
        runTest {
            val conn = MockQuicConnection(QuicConnectionState.Idle)
            conn.transitionTo(QuicConnectionState.Handshaking)
            assertIs<QuicConnectionState.Handshaking>(conn.state.value)
        }

    @Test
    fun transitionTo_established() =
        runTest {
            val conn = MockQuicConnection(QuicConnectionState.Handshaking)
            conn.transitionTo(QuicConnectionState.Established("h3"))
            val state = conn.state.value
            assertIs<QuicConnectionState.Established>(state)
            assertEquals("h3", state.negotiatedAlpn)
        }

    @Test
    fun transitionTo_draining() =
        runTest {
            val conn = MockQuicConnection()
            conn.transitionTo(QuicConnectionState.Draining)
            assertIs<QuicConnectionState.Draining>(conn.state.value)
        }

    private fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)
}
