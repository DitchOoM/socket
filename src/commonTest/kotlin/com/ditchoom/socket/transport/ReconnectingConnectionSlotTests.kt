package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The held connection and the published [ConnectionState] move together on every transition. */
class ReconnectingConnectionSlotTests {
    private class CountingConnection(
        private val stream: Flow<String>,
        private val closeFailure: Throwable? = null,
    ) : Connection<String> {
        override val id: Long = 0L
        var closeCount = 0
            private set
        val sent = mutableListOf<String>()

        override suspend fun send(message: String) {
            sent += message
        }

        override suspend fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }

        override fun receive(): Flow<String> = stream
    }

    private val retryImmediately =
        ReconnectionClassifier { ReconnectDecision.RetryAfter(Duration.ZERO) }

    /** A clean stream end vacates the slot: the connection is released, not left behind a Disconnected state. */
    @Test
    fun cleanStreamEndClosesTheConnectionAndPublishesDisconnected() =
        runTest {
            val connections = mutableListOf<CountingConnection>()
            val connection =
                ReconnectingConnection(
                    connect = { CountingConnection(emptyFlow()).also { connections += it } },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            connection.receive().collect { }

            assertIs<ConnectionState.Disconnected>(connection.state.value)
            assertEquals(
                1,
                connections.single().closeCount,
                "state is Disconnected but the connection it held was never closed",
            )
        }

    /** After a clean end the slot is empty, so close() has nothing left to close a second time. */
    @Test
    fun closeAfterACleanEndDoesNotCloseTheConnectionAgain() =
        runTest {
            val connections = mutableListOf<CountingConnection>()
            val connection =
                ReconnectingConnection(
                    connect = { CountingConnection(emptyFlow()).also { connections += it } },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            connection.receive().collect { }
            connection.close()

            assertEquals(1, connections.single().closeCount)
            assertIs<ConnectionState.Disconnected>(connection.state.value)
        }

    @Test
    fun sendAfterACleanEndIsRefused() =
        runTest {
            val connection =
                ReconnectingConnection(
                    connect = { CountingConnection(emptyFlow()) },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            connection.receive().collect { }

            val refusal = assertFailsWith<IllegalStateException> { connection.send("after clean end") }
            assertTrue(refusal.message?.contains("receive()") == true, "got: ${refusal.message}")
        }

    /** While a connection is held the state is Connected and send() reaches that connection. */
    @Test
    fun heldConnectionIsConnectedAndReceivesSends() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val held = CountingConnection(flow { gate.await() })
            val connection =
                ReconnectingConnection(
                    connect = { held },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )
            assertEquals(ConnectionState.Initialized, connection.state.value)

            val collector = launch { connection.receive().collect { } }
            connection.state.first { it == ConnectionState.Connected }
            connection.send("hello")
            assertEquals(listOf("hello"), held.sent)

            connection.close()
            assertEquals(1, held.closeCount)
            assertIs<ConnectionState.Disconnected>(connection.state.value)
            gate.complete(Unit)
            collector.join()
        }

    /** A failed stream vacates: the state carries the cause and the next attempt connects afresh. */
    @Test
    fun failedStreamPublishesTheCauseThenReconnects() =
        runTest {
            val cause = SocketIOException("boom")
            var attempts = 0
            val second = CompletableDeferred<Unit>()
            val connection =
                ReconnectingConnection(
                    connect = {
                        attempts++
                        if (attempts == 1) {
                            CountingConnection(flow { throw cause })
                        } else {
                            CountingConnection(flow { second.await() })
                        }
                    },
                    classifier = retryImmediately,
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            val collector = launch { connection.receive().collect { } }
            connection.state.first { it == ConnectionState.Connected && attempts == 2 }
            assertEquals(2, attempts)

            connection.close()
            second.complete(Unit)
            collector.join()
            assertIs<ConnectionState.Disconnected>(connection.state.value)
        }

    /** Every exit from a held connection closes it, the failure path included. */
    @Test
    fun failedStreamClosesTheConnectionItHeld() =
        runTest {
            val connections = mutableListOf<CountingConnection>()
            val second = CompletableDeferred<Unit>()
            val connection =
                ReconnectingConnection(
                    connect = {
                        val stream =
                            if (connections.isEmpty()) {
                                flow<String> {
                                    throw SocketIOException(
                                        "boom",
                                    )
                                }
                            } else {
                                flow { second.await() }
                            }
                        CountingConnection(stream).also { connections += it }
                    },
                    classifier = retryImmediately,
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            val collector = launch { connection.receive().collect { } }
            connection.state.first { it == ConnectionState.Connected && connections.size == 2 }

            assertEquals(1, connections.first().closeCount, "the failed connection was dropped without being closed")
            connection.close()
            second.complete(Unit)
            collector.join()
        }

    /** A close() that throws while releasing a failed connection neither masks the stream's error nor ends the loop. */
    @Test
    fun closeFailureOnTheFailurePathDoesNotMaskTheOriginalError() =
        runTest {
            val original = SocketIOException("boom")
            val connection =
                ReconnectingConnection(
                    connect = {
                        CountingConnection(flow { throw original }, closeFailure = IllegalStateException("close failed"))
                    },
                    classifier = { ReconnectDecision.GiveUp },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            val thrown = assertFailsWith<SocketIOException> { connection.receive().collect { } }
            assertEquals("boom", thrown.message)
        }

    @Test
    fun closeFailureOnTheFailurePathDoesNotEndTheReconnectLoop() =
        runTest {
            var attempts = 0
            val second = CompletableDeferred<Unit>()
            val connection =
                ReconnectingConnection(
                    connect = {
                        attempts++
                        if (attempts == 1) {
                            CountingConnection(
                                flow { throw SocketIOException("boom") },
                                closeFailure = IllegalStateException("close failed"),
                            )
                        } else {
                            CountingConnection(flow { second.await() })
                        }
                    },
                    classifier = retryImmediately,
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            val collector = launch { connection.receive().collect { } }
            connection.state.first { it == ConnectionState.Connected && attempts == 2 }
            connection.close()
            second.complete(Unit)
            collector.join()
        }

    /** A liveness-driven teardown closes the connection exactly once, however many exits observe it. */
    @Test
    fun livenessLossClosesTheConnectionExactlyOnce() =
        runTest {
            val monitor = MockNetworkMonitor(NetworkState.LinkLocal(NetworkId.Unidentified))
            val connections = mutableListOf<CountingConnection>()
            val firstClosed = CompletableDeferred<Unit>()
            val connection =
                ReconnectingConnection(
                    connect = {
                        val stream = if (connections.isEmpty()) flow<String> { firstClosed.await() } else emptyFlow()
                        object : Connection<String> {
                            val inner = CountingConnection(stream)
                            override val id: Long = 0L

                            override suspend fun send(message: String) = inner.send(message)

                            override suspend fun close() {
                                inner.close()
                                firstClosed.complete(Unit)
                            }

                            override fun receive(): Flow<String> = inner.receive()
                        }.also { connections += it.inner }
                    },
                    monitorFactory = { monitor },
                    liveness = { Liveness.Result.Dead },
                )

            val collector = launch { connection.receive().collect { } }
            connection.state.first { it == ConnectionState.Connected }
            advanceUntilIdle()
            monitor.setNetworkId(NetworkId.KindOnly(NetworkKind.Wifi))
            collector.join()

            assertEquals(2, connections.size)
            assertEquals(1, connections.first().closeCount, "liveness teardown plus the loop's exit closed it more than once")
        }

    /** Disconnected after close() is terminal: the loop winding down does not republish over it. */
    @Test
    fun loopWindingDownAfterCloseDoesNotRepublishDisconnected() =
        runTest {
            val closed = CompletableDeferred<Unit>()
            val held =
                object : Connection<String> {
                    override val id: Long = 0L

                    override suspend fun send(message: String) = Unit

                    override suspend fun close() {
                        closed.complete(Unit)
                    }

                    override fun receive(): Flow<String> = flow { closed.await() }
                }
            val connection =
                ReconnectingConnection(
                    connect = { held },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            val collector = launch { connection.receive().collect { } }
            connection.state.first { it == ConnectionState.Connected }
            connection.close()
            val terminal = connection.state.value
            collector.join()

            assertTrue(terminal === connection.state.value, "the state published by close() was overwritten")
        }
}
