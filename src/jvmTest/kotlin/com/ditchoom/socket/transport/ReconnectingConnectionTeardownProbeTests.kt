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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
