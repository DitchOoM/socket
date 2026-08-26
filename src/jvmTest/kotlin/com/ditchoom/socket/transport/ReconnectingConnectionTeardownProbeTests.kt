package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Asks whether `close()` actually stops what `receive()` started, using [assertNothingSurvivesTeardown].
 *
 * The teardown-once work proved `close()` runs its body exactly once. That is a different claim from
 * "`close()` ends the connection's activity", and nothing was checking the second one.
 *
 * The reconnect loop's own condition is `while (currentCoroutineContext().isActive)`. Closing does
 * not cancel the collector's context, so whether the loop stops depends entirely on how the inner
 * connection's `receive()` reacts to being closed underneath it — and the realistic reaction, a
 * socket closed under a reader, is an [SocketIOException], which is exactly what
 * [com.ditchoom.socket.DefaultReconnectionClassifier] classifies as retryable.
 */
class ReconnectingConnectionTeardownProbeTests {
    /**
     * A connection whose `receive()` parks until the connection is closed and then fails the way a
     * real transport does when the socket goes away underneath the reader.
     */
    private class ClosableConnection : Connection<String> {
        override val id: Long = 0L
        private val closed = CompletableDeferred<Unit>()

        /** Whether anyone closed this connection — the witness for the connect/close handoff. */
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

    @Test
    fun closeStopsTheReconnectLoop() =
        runBlocking(Dispatchers.Default) {
            withTimeout(60.seconds) {
                val connectCalls = AtomicInteger(0)
                val firstConnected = CompletableDeferred<Unit>()

                assertNothingSurvivesTeardown("reconnecting-close-stops-loop") { scope ->
                    val connection =
                        ReconnectingConnection(
                            connect = {
                                connectCalls.incrementAndGet()
                                firstConnected.complete(Unit)
                                ClosableConnection()
                            },
                            monitorFactory = { NetworkMonitor.AlwaysAvailable },
                        )
                    scope.launch { runCatching { connection.receive().collect { } } }
                    firstConnected.await()
                    connection.close()
                }

                assertEquals(
                    1,
                    connectCalls.get(),
                    "connect() ran ${connectCalls.get()} times: after close() the reconnect loop " +
                        "kept reconnecting a connection the caller had already closed",
                )
            }
        }

    /**
     * Closes while `connect()` is still in flight — the window the handoff [kotlinx.coroutines.sync.Mutex]
     * exists to order, hit deterministically rather than sampled.
     *
     * [closeStopsTheReconnectLoop] above exercises this window only by luck: it awaits a signal
     * raised *inside* the connect lambda and then races `close()` against that lambda returning.
     * It caught the bug — but under the full suite's load, not on a quiet machine, and a run that
     * passes cannot distinguish "the window was hit and handled" from "the window was never hit".
     * That is the vacuity `ReconnectingConnectionCollectorRaceTests` guards against with its
     * `rejections > 0` assertion, and this test had no equivalent.
     *
     * Rather than count contention, this removes the race from the harness: `connect()` parks until
     * the test releases it, so `close()` is *guaranteed* to run while a connection is being
     * established. The assertions then hold unconditionally — no attempt count, no vacuity to guard.
     *
     * The connection minted after `close()` has already run belongs to nobody: `close()` read
     * [currentConnection] as null and closed nothing, so the reconnect loop must notice it lost the
     * handoff and close its own. Before the handoff lock it adopted it instead and parked inside
     * `receive()` forever.
     */
    @Test
    fun closeWhileConnectIsInFlightStillClosesTheConnection() =
        runBlocking(Dispatchers.Default) {
            withTimeout(60.seconds) {
                val connectEntered = CompletableDeferred<Unit>()
                val releaseConnect = CompletableDeferred<Unit>()
                val handedOut = CopyOnWriteArrayList<ClosableConnection>()

                assertNothingSurvivesTeardown("close-during-connect") { scope ->
                    val connection =
                        ReconnectingConnection(
                            connect = {
                                connectEntered.complete(Unit)
                                releaseConnect.await()
                                ClosableConnection().also { handedOut += it }
                            },
                            monitorFactory = { NetworkMonitor.AlwaysAvailable },
                        )
                    scope.launch { runCatching { connection.receive().collect { } } }

                    // connect() is now inside the lambda and parked: the window is open, not hoped for.
                    connectEntered.await()
                    connection.close()
                    releaseConnect.complete(Unit)
                }

                assertEquals(
                    1,
                    handedOut.size,
                    "expected exactly one connection to be minted; got ${handedOut.size}",
                )
                assertTrue(
                    handedOut.single().wasClosed,
                    "close() ran while connect() was in flight, so it read a null currentConnection " +
                        "and closed nothing. The reconnect loop then had to close the connection it " +
                        "had just been handed, and did not — it is live and unreferenced.",
                )
            }
        }
}
