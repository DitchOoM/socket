package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `close()` promises, asserted on **every** platform.
 *
 * ## Why these live in commonTest when the probes do not
 *
 * The two bug classes this suite came out of have different platform exposure, and conflating them
 * would under-test one of them.
 *
 * `PooledBuffer`'s refcount race was a *shared-memory* data race: real threads, real preemption, so
 * it cannot occur on JS or wasm at all. Lincheck is the right tool and JVM-only is the right scope.
 *
 * These two are *coroutine-interleaving* bugs. They are about suspension points rather than threads:
 * `connect()` suspends, `close()` interleaves at that point, and nothing about that requires a second
 * thread. They are reachable on single-threaded JS and wasm exactly as they are on the JVM — but the
 * tests that found them (`ReconnectingConnectionTeardownProbeTests`) are JVM-only, because
 * `DebugProbes` is. The bug class that affects every platform was being tested on one.
 *
 * Nothing here needs an agent. Both cases are deterministic by construction — `connect()` parks until
 * the test releases it, so the interleaving is chosen rather than raced — which is what lets them run
 * under [runTest] on all six platform families instead of needing a leak enumerator.
 *
 * `DebugProbes` earns its JVM-only keep for the question these cannot ask: *what else* is still
 * alive, and where was it started. That is a diagnostic, not a contract.
 */
class ReconnectingConnectionCloseContractTests {
    /** Parks its reader until closed, then fails the way a real transport does. */
    private class ParkingConnection : Connection<String> {
        override val id: Long = 0L
        private val closed = CompletableDeferred<Unit>()

        val wasClosed: Boolean get() = closed.isCompleted

        override suspend fun send(message: String) = Unit

        override suspend fun close() {
            closed.complete(Unit)
        }

        override fun receive(): Flow<String> =
            flow {
                closed.await()
                throw SocketIOException("connection closed underneath the reader")
            }
    }

    /**
     * `close()` ends the reconnect loop rather than letting it reconnect.
     *
     * The loop's condition is `isActive`, and `close()` does not cancel the collector's context — so
     * before the `!closed` guard, `close()` shut the transport, the inner read failed *because of
     * that*, the classifier read it as a retryable fault, and the loop opened a replacement that
     * nothing held a reference to close.
     */
    @Test
    fun closeEndsTheReconnectLoopInsteadOfReconnecting() =
        runTest {
            var connectCalls = 0
            val connected = CompletableDeferred<Unit>()
            val connection =
                ReconnectingConnection(
                    connect = {
                        connectCalls++
                        ParkingConnection().also { connected.complete(Unit) }
                    },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            val collector = launch { runCatching { connection.receive().collect { } } }
            connected.await()
            connection.close()
            collector.join()

            assertEquals(
                1,
                connectCalls,
                "connect() ran $connectCalls times: after close() the loop reconnected a connection " +
                    "the caller had already closed",
            )
        }

    /**
     * `close()` racing connection establishment still closes the connection.
     *
     * The loop does `connect()` then `currentConnection = conn` as two steps. A `close()` that reads
     * the field between them closes nothing, and the loop then adopted the orphan and parked inside
     * `receive()` forever. The window is entered deliberately here — `connect()` parks until released
     * — so this asserts unconditionally rather than sampling a race.
     */
    @Test
    fun closeWhileConnectIsInFlightStillClosesTheConnection() =
        runTest {
            val connectEntered = CompletableDeferred<Unit>()
            val releaseConnect = CompletableDeferred<Unit>()
            val handedOut = mutableListOf<ParkingConnection>()

            val connection =
                ReconnectingConnection(
                    connect = {
                        connectEntered.complete(Unit)
                        releaseConnect.await()
                        ParkingConnection().also { handedOut += it }
                    },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            val collector = launch { runCatching { connection.receive().collect { } } }

            // connect() is inside the lambda and parked: the window is open, not hoped for.
            connectEntered.await()
            connection.close()
            releaseConnect.complete(Unit)
            collector.join()

            assertEquals(1, handedOut.size, "expected exactly one connection to be minted")
            assertTrue(
                handedOut.single().wasClosed,
                "close() ran while connect() was in flight, so it read a null currentConnection and " +
                    "closed nothing. The loop then had to close the connection it had just been " +
                    "handed, and did not — it is live and unreferenced.",
            )
        }
}
