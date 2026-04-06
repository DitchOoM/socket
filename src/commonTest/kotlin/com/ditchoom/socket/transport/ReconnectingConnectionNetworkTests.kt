package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Controllable [NetworkMonitor] for testing. */
class MockNetworkMonitor(
    initial: NetworkAvailability = NetworkAvailability.AVAILABLE,
) : NetworkMonitor {
    private val _availability = MutableStateFlow(initial)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    fun set(value: NetworkAvailability) {
        _availability.value = value
    }

    override fun close() {}
}

class ReconnectingConnectionNetworkTests {
    private val testOptions = ConnectionOptions(readTimeout = 5.seconds, writeTimeout = 5.seconds)

    private fun createCodecConnection(clientStream: ByteStream): CodecConnection<String> =
        CodecConnection(
            stream = clientStream,
            codec = TestStringCodec,
            pool =
                com.ditchoom.buffer.pool
                    .BufferPool(),
            options = testOptions,
        )

    // ── NetworkMonitor integration ──

    @Test
    fun networkAvailableResetsBackoff() =
        runTest {
            var connectCount = 0
            val monitor = MockNetworkMonitor(NetworkAvailability.UNAVAILABLE)

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
                    // Long backoff that would timeout the test if not reset
                    classifier = ReconnectionClassifier { ReconnectDecision.RetryAfter(60.seconds) },
                    networkMonitor = monitor,
                )

            val job =
                launch {
                    val messages = conn.receive().toList()
                    assertEquals(listOf("recovered"), messages)
                }

            // Give the first connect attempt time to fail and enter backoff
            testScheduler.advanceTimeBy(100.milliseconds)

            // Simulate network becoming available — should reset backoff
            monitor.set(NetworkAvailability.AVAILABLE)

            job.join()
            assertEquals(2, connectCount)
        }

    @Test
    fun alwaysAvailableDoesNotInterfereWithBackoff() =
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
                        serverCodec.send("ok")
                        serverCodec.close()
                        codec
                    },
                    classifier = ReconnectionClassifier { ReconnectDecision.RetryAfter(1.milliseconds) },
                    // Default: AlwaysAvailable
                )

            val messages = conn.receive().toList()
            assertEquals(listOf("ok"), messages)
            assertEquals(2, connectCount)
        }

    @Test
    fun backwardCompatibleWithoutNetworkMonitor() =
        runTest {
            val conn =
                ReconnectingConnection(
                    connect = {
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
        }

    // ── lastMessageReceived tracking ──

    @Test
    fun lastMessageReceivedUpdatesOnEachMessage() =
        runTest {
            val conn =
                ReconnectingConnection(
                    connect = {
                        val (client, server) = MemoryTransport.createPair()
                        val codec = createCodecConnection(client)
                        val serverCodec = createCodecConnection(server)
                        serverCodec.send("msg1")
                        serverCodec.send("msg2")
                        serverCodec.close()
                        codec
                    },
                )

            assertNull(conn.lastMessageReceived.value, "Should be null before receiving")

            val messages = conn.receive().toList()
            assertEquals(listOf("msg1", "msg2"), messages)

            assertNotNull(conn.lastMessageReceived.value, "Should be set after receiving messages")
        }

    @Test
    fun lastMessageReceivedIsNullBeforeFirstMessage() =
        runTest {
            val conn =
                ReconnectingConnection(
                    connect = {
                        val (client, server) = MemoryTransport.createPair()
                        val codec = createCodecConnection(client)
                        val serverCodec = createCodecConnection(server)
                        serverCodec.close()
                        codec
                    },
                )

            assertNull(conn.lastMessageReceived.value)

            // Stream ends cleanly with no messages
            val messages = conn.receive().toList()
            assertEquals(emptyList(), messages)

            // Still null — no messages were received
            assertNull(conn.lastMessageReceived.value)
        }

    // ── state observable with network monitor ──

    @Test
    fun stateTransitionsWithNetworkMonitor() =
        runTest {
            val monitor = MockNetworkMonitor()

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
                    networkMonitor = monitor,
                )

            assertIs<ConnectionState.Initialized>(conn.state.value)

            conn.receive().toList()

            assertIs<ConnectionState.Disconnected>(conn.state.value)
        }

    // ── NetworkMonitor.AlwaysAvailable ──

    @Test
    fun alwaysAvailableReportsAvailable() {
        assertEquals(NetworkAvailability.AVAILABLE, NetworkMonitor.AlwaysAvailable.availability.value)
    }

    @Test
    fun alwaysAvailableCloseIsIdempotent() {
        NetworkMonitor.AlwaysAvailable.close()
        NetworkMonitor.AlwaysAvailable.close() // should not throw
    }
}
