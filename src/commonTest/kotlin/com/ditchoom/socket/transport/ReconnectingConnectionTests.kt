package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReconnectingConnectionTests {
    private val testOptions = ConnectionOptions(readTimeout = 5.seconds, writeTimeout = 5.seconds)

    private fun createCodecConnection(clientStream: ByteStream): CodecConnection<String> =
        CodecConnection(
            stream = clientStream,
            codec = TestStringCodec,
            options = testOptions,
        )

    // ── reconnects after failure ──

    @Test
    fun reconnectsAfterTransportFailure() =
        runTest {
            var connectCount = 0

            val conn =
                ReconnectingConnection(
                    connect = {
                        connectCount++
                        if (connectCount == 1) {
                            // First attempt: fails during connect
                            throw SocketIOException("connection lost")
                        }
                        // Second attempt: succeeds
                        val (client, server) = MemoryTransport.createPair()
                        val codec = createCodecConnection(client)
                        val serverCodec = createCodecConnection(server)
                        serverCodec.send("recovered")
                        serverCodec.close()
                        codec
                    },
                    classifier = ReconnectionClassifier { _ -> ReconnectDecision.RetryAfter(1.milliseconds) },
                )

            val messages = conn.receive().toList()
            assertEquals(listOf("recovered"), messages)
            assertEquals(2, connectCount)
        }

    // ── gives up on non-recoverable errors ──

    @Test
    fun giveUpTerminatesFlow() =
        runTest {
            val conn =
                ReconnectingConnection<String>(
                    connect = {
                        throw SocketIOException("fatal error")
                    },
                    classifier = ReconnectionClassifier { _ -> ReconnectDecision.GiveUp },
                )

            assertFailsWith<SocketIOException> {
                conn.receive().toList()
            }
        }

    // ── clean stream end does not retry ──

    @Test
    fun cleanStreamEndCompletesWithoutRetry() =
        runTest {
            var connectCount = 0

            val conn =
                ReconnectingConnection(
                    connect = {
                        connectCount++
                        val (client, server) = MemoryTransport.createPair()
                        val codec = createCodecConnection(client)
                        val serverCodec = createCodecConnection(server)
                        serverCodec.send("hello")
                        serverCodec.close()
                        codec
                    },
                )

            val messages = conn.receive().toList()
            assertEquals(listOf("hello"), messages)
            assertEquals(1, connectCount, "Should not retry after clean stream end")
        }

    // ── send() when not connected ──

    @Test
    fun sendThrowsWhenNotConnected() =
        runTest {
            val conn =
                ReconnectingConnection(
                    connect = {
                        val (client, _) = MemoryTransport.createPair()
                        createCodecConnection(client)
                    },
                )

            // Not connected yet (haven't called receive)
            assertFailsWith<IllegalStateException> {
                conn.send("should fail")
            }
        }

    // ── close is idempotent ──

    @Test
    fun closeIsIdempotent() =
        runTest {
            val conn =
                ReconnectingConnection(
                    connect = {
                        val (client, _) = MemoryTransport.createPair()
                        createCodecConnection(client)
                    },
                )

            conn.close()
            conn.close() // should not throw
        }

    // ── receive after close throws ──

    @Test
    fun receiveAfterCloseThrows() =
        runTest {
            val conn =
                ReconnectingConnection(
                    connect = {
                        val (client, _) = MemoryTransport.createPair()
                        createCodecConnection(client)
                    },
                )

            conn.close()
            assertFailsWith<IllegalStateException> {
                conn.receive()
            }
        }

    // ── state transitions ──

    @Test
    fun stateTransitionsOnConnectAndDisconnect() =
        runTest {
            val conn =
                ReconnectingConnection(
                    connect = {
                        val (client, server) = MemoryTransport.createPair()
                        val codec = createCodecConnection(client)
                        val serverCodec = createCodecConnection(server)
                        serverCodec.send("msg")
                        serverCodec.close()
                        codec
                    },
                )

            assertIs<ConnectionState.Initialized>(conn.state.value)

            conn.receive().toList()

            assertIs<ConnectionState.Disconnected>(conn.state.value)
        }

    // ── resetBackoff ──

    @Test
    fun resetBackoffSkipsDelay() =
        runTest {
            var connectCount = 0

            val conn =
                ReconnectingConnection(
                    connect = {
                        connectCount++
                        if (connectCount == 1) {
                            throw SocketIOException("transient")
                        }
                        val (client, server) = MemoryTransport.createPair()
                        val codec = createCodecConnection(client)
                        val serverCodec = createCodecConnection(server)
                        serverCodec.send("recovered")
                        serverCodec.close()
                        codec
                    },
                    classifier = ReconnectionClassifier { ReconnectDecision.RetryAfter(60.seconds) },
                )

            // Reset backoff so it doesn't wait 60 seconds
            conn.resetBackoff()

            val messages = conn.receive().toList()
            assertEquals(listOf("recovered"), messages)
            assertEquals(2, connectCount)
        }

    // ── send after close throws ──

    @Test
    fun sendAfterCloseThrows() =
        runTest {
            val conn =
                ReconnectingConnection(
                    connect = {
                        val (client, _) = MemoryTransport.createPair()
                        createCodecConnection(client)
                    },
                )

            conn.close()
            assertFailsWith<IllegalStateException> {
                conn.send("should fail")
            }
        }
}
