package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A liveness verdict belongs to the connection it was measured on. A probe that outlives its own
 * connection (one that does not honour cancellation) must not be able to condemn the next one.
 */
class ReconnectingConnectionStaleLivenessTests {
    /** A connection whose inbound stream is whatever the test scripts. */
    private class ScriptedConnection(
        private val inbound: Flow<String>,
    ) : Connection<String> {
        override val id: Long = 0L

        override suspend fun send(message: String) = Unit

        override suspend fun close() = Unit

        override fun receive(): Flow<String> = inbound
    }

    /**
     * Connection 1 is probed after a path change; the probe ignores cancellation and parks. Connection 1
     * then fails and connection 2 is adopted. Only then does the old probe answer Dead. Connection 2 ends
     * cleanly, which must finish the flow: no third connect, no "liveness lost" verdict on connection 2.
     */
    @Test
    fun aStaleDeadVerdictDoesNotCondemnTheNextConnection() =
        runTest {
            val monitor = MockNetworkMonitor(NetworkState.LinkLocal(NetworkId.Unidentified))
            val firstFails = CompletableDeferred<Unit>()
            val secondEnds = CompletableDeferred<Unit>()
            val staleProbeMayAnswer = CompletableDeferred<Unit>()
            var connects = 0
            val connection =
                ReconnectingConnection(
                    connect = {
                        connects++
                        when (connects) {
                            1 ->
                                ScriptedConnection(
                                    flow {
                                        firstFails.await()
                                        throw SocketIOException("first connection dropped")
                                    },
                                )
                            2 -> ScriptedConnection(flow { secondEnds.await() })
                            else -> ScriptedConnection(flow { })
                        }
                    },
                    classifier = ReconnectionClassifier { ReconnectDecision.RetryAfter(Duration.ZERO) },
                    monitorFactory = { monitor },
                    liveness = {
                        // Ignores cancellation, as a probe wrapping a blocking call may.
                        withContext(NonCancellable) { staleProbeMayAnswer.await() }
                        Liveness.Result.Dead
                    },
                )
            val collector = launch { connection.receive().collect { } }

            advanceUntilIdle()
            assertEquals(1, connects)
            monitor.setNetworkId(NetworkId.KindOnly(NetworkKind.Wifi))
            advanceUntilIdle() // the probe on connection 1 is now parked, uncancellable

            firstFails.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, connects, "connection 2 should have been adopted")
            assertIs<ConnectionState.Connected>(connection.state.value)

            staleProbeMayAnswer.complete(Unit) // connection 1's probe answers Dead, long after connection 1
            advanceUntilIdle()
            secondEnds.complete(Unit) // connection 2 ends cleanly
            advanceUntilIdle()

            assertEquals(2, connects, "a verdict about connection 1 reconnected connection 2")
            assertTrue(collector.isCompleted, "a clean end of connection 2 should finish the flow")
            assertIs<ConnectionState.Disconnected>(connection.state.value)
        }
}
