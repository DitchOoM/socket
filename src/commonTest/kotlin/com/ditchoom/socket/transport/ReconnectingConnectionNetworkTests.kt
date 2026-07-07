package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Controllable [NetworkMonitor] for testing. */
class MockNetworkMonitor(
    initial: NetworkAvailability = NetworkAvailability.AVAILABLE,
    initialNetworkId: NetworkId = NetworkId.Unidentified,
) : NetworkMonitor {
    private val _availability = MutableStateFlow(initial)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val _networkId = MutableStateFlow(initialNetworkId)
    override val networkId: StateFlow<NetworkId> = _networkId.asStateFlow()

    fun set(value: NetworkAvailability) {
        _availability.value = value
    }

    fun setNetworkId(value: NetworkId) {
        _networkId.value = value
    }

    override fun close() {}
}

class ReconnectingConnectionNetworkTests {
    private val testOptions =
        TransportConfig(
            readPolicy = ReadPolicy.Bounded(5.seconds),
            writePolicy = WritePolicy.Bounded(5.seconds),
        )

    private fun createCodecConnection(clientStream: ByteStream): CodecConnection<String> =
        CodecConnection(
            stream = clientStream,
            codec = TestStringCodec,
            config = testOptions,
        )

    // A connection whose reads park indefinitely (until closed) — used by the liveness tests so a
    // connection with no in-flight data stays open until the liveness probe tears it down, rather
    // than tripping the bounded read deadline.
    private val parkingOptions =
        TransportConfig(
            readPolicy = ReadPolicy.UntilClosed,
            writePolicy = WritePolicy.Bounded(5.seconds),
        )

    private fun createParkingConnection(stream: ByteStream): CodecConnection<String> =
        CodecConnection(stream = stream, codec = TestStringCodec, config = parkingOptions)

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
                    monitorFactory = { monitor },
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
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            val messages = conn.receive().toList()
            assertEquals(listOf("ok"), messages)
            assertEquals(2, connectCount)
        }

    @Test
    fun networkIdChangeCutsBackoffShort() =
        runTest {
            var connectCount = 0
            // Availability stays UNAVAILABLE so the availability→resetBackoff path never fires;
            // the only thing that can cut the 60s backoff short is a networkId change.
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
                    classifier = ReconnectionClassifier { ReconnectDecision.RetryAfter(60.seconds) },
                    monitorFactory = { monitor },
                )

            val job =
                launch {
                    val messages = conn.receive().toList()
                    assertEquals(listOf("recovered"), messages)
                }

            // Let the first attempt fail and park in the 60s backoff (no virtual time advanced).
            runCurrent()

            // A path change while parked must abandon the remaining backoff and re-attempt now.
            monitor.setNetworkId(NetworkId.KindOnly(NetworkKind.Cellular))

            job.join()

            assertEquals(2, connectCount)
            // Proves the wait was actually interrupted, not that virtual time reached 60s.
            assertTrue(
                testScheduler.currentTime < 60.seconds.inWholeMilliseconds,
                "backoff should have been cut short by the network change, elapsed=${testScheduler.currentTime}ms",
            )
        }

    // ── Liveness seam ──

    @Test
    fun livenessDeadReconnectsOnNetworkChange() =
        runTest {
            var connectCount = 0
            var probeCount = 0
            val monitor = MockNetworkMonitor(NetworkAvailability.UNAVAILABLE)

            val conn =
                ReconnectingConnection(
                    connect = {
                        connectCount++
                        if (connectCount == 1) {
                            // First connection stays open with no data — the client parks in
                            // receive() (UntilClosed) until the liveness probe tears it down.
                            val (client, _) = MemoryTransport.createPair(parkingOptions)
                            createParkingConnection(client)
                        } else {
                            val (client, server) = MemoryTransport.createPair()
                            val codec = createCodecConnection(client)
                            val serverCodec = createCodecConnection(server)
                            serverCodec.send("recovered")
                            serverCodec.close()
                            codec
                        }
                    },
                    monitorFactory = { monitor },
                    liveness = {
                        probeCount++
                        // First probe: report the (still-parked) connection dead.
                        if (probeCount == 1) Liveness.Result.Dead else Liveness.Result.Alive
                    },
                )

            val job =
                launch {
                    val messages = conn.receive().toList()
                    assertEquals(listOf("recovered"), messages)
                }

            // First connection established and parked in receive().
            advanceUntilIdle()
            assertEquals(1, connectCount)

            // A path change drives the liveness probe; it reports Dead → tear down + reconnect.
            monitor.setNetworkId(NetworkId.KindOnly(NetworkKind.Wifi))
            job.join()

            assertEquals(1, probeCount)
            assertEquals(2, connectCount)
        }

    @Test
    fun livenessAliveKeepsConnection() =
        runTest {
            var connectCount = 0
            var probeCount = 0
            val monitor = MockNetworkMonitor(NetworkAvailability.UNAVAILABLE)
            var openServer: CodecConnection<String>? = null

            val conn =
                ReconnectingConnection(
                    connect = {
                        connectCount++
                        val (client, server) = MemoryTransport.createPair(parkingOptions)
                        val codec = createParkingConnection(client)
                        val serverCodec = createParkingConnection(server)
                        serverCodec.send("hello")
                        openServer = serverCodec // left open; closed by the test below
                        codec
                    },
                    monitorFactory = { monitor },
                    liveness = {
                        probeCount++
                        Liveness.Result.Alive
                    },
                )

            val received = mutableListOf<String>()
            val job =
                launch {
                    conn.receive().collect { received.add(it) }
                }

            advanceUntilIdle()
            assertEquals(listOf("hello"), received)

            // Path change → liveness probes, reports Alive → connection is kept, no reconnect.
            monitor.setNetworkId(NetworkId.KindOnly(NetworkKind.Wifi))
            advanceUntilIdle()

            assertEquals(1, connectCount, "an Alive probe must not tear down the connection")
            assertTrue(probeCount >= 1, "the probe should have run on the path change")

            // Explicit close ends the stream cleanly — no liveness-driven reconnect.
            openServer?.close()
            job.join()
            assertEquals(1, connectCount)
        }

    @Test
    fun livenessInertWithoutPathChanges() =
        runTest {
            var connectCount = 0
            var probeCount = 0

            val conn =
                ReconnectingConnection(
                    connect = {
                        connectCount++
                        val (client, server) = MemoryTransport.createPair()
                        val codec = createCodecConnection(client)
                        val serverCodec = createCodecConnection(server)
                        serverCodec.send("ok")
                        serverCodec.close()
                        codec
                    },
                    // AlwaysAvailable never reports a networkId change → the seam is never driven.
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                    liveness = {
                        probeCount++
                        Liveness.Result.Dead
                    },
                )

            val messages = conn.receive().toList()
            assertEquals(listOf("ok"), messages)
            assertEquals(1, connectCount)
            assertEquals(0, probeCount, "liveness must not be probed without a path-change signal")
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
                    monitorFactory = { monitor },
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
