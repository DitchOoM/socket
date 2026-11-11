package com.ditchoom.socket

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.ditchoom.data.readBuffer

/**
 * A started `nw_connection_t` must be cancelled by whoever holds it last, and a listener's close must
 * resume exactly once, whichever thread NW reports `cancelled` on.
 */
class AppleListenerHandleLifetimeTests {
    /**
     * S3. `close()` is resumed by the NW `cancelled` callback (a concurrent queue) and by the closer
     * itself when it sees the listener is no longer ready. Both can run at once; a second resume of the
     * continuation aborts a Kotlin/Native process.
     */
    @Test
    fun concurrentCancelledReportsResumeTheCloseExactlyOnce() =
        runBlocking {
            val escaped = AtomicReference(listOf<Throwable>())
            val callbacks =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Default +
                        CoroutineExceptionHandler { _, t ->
                            while (true) {
                                val seen = escaped.value
                                if (escaped.compareAndSet(seen, seen + t)) break
                            }
                        },
                )
            try {
                repeat(ROUNDS) { round ->
                    val outcome =
                        runCatching {
                            suspendCoroutine<Unit> { continuation ->
                                val settle = ListenerCancelSettle(continuation)
                                repeat(CALLBACK_THREADS) { callbacks.launch { settle.on() } }
                            }
                        }
                    assertTrue(outcome.isSuccess, "round $round: $outcome")
                }
                delay(100.milliseconds) // late callers of the last round
            } finally {
                callbacks.cancel()
            }
            assertEquals(emptyList(), escaped.value.map { it::class.simpleName + ": " + it.message }.take(3))
        }

    /**
     * S5. A connection that became ready after the accept channel closed has no consumer, ever: the
     * hand-out must cancel it instead of dropping it.
     */
    @Test
    fun aReadyConnectionRefusedByAClosedChannelIsCancelled() {
        val socket = NWSocketWrapper()
        val closed = Channel<NWSocketWrapper>(Channel.UNLIMITED)
        closed.close()
        closed.handOut(socket)
        assertTrue(socket.closedLocally, "the socket a closed channel refused was left open")

        val open = Channel<NWSocketWrapper>(Channel.UNLIMITED)
        val taken = NWSocketWrapper()
        open.handOut(taken)
        assertFalse(taken.closedLocally, "a socket the channel took must stay open for its consumer")
        assertTrue(open.tryReceive().getOrNull() === taken)
    }

    /**
     * S5. Connections that are ready and queued for an accept nobody made when the server closes: the
     * peers must see the connection end, not an open socket that nothing serves.
     */
    @Test
    fun connectionsNobodyAcceptedAreClosedWithTheServer() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            val server = ServerSocket.allocate()
            server.bind() // no collector: every arriving connection is queued and never taken
            val clients =
                List(3) {
                    ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds)).also {
                        it.open(server.port(), "127.0.0.1")
                    }
                }
            delay(500.milliseconds) // each is ready on the server side and sitting in the accept channel
            server.close()
            for ((i, client) in clients.withIndex()) {
                val outcome = runCatching { client.readBuffer(3.seconds) }
                assertTrue(
                    outcome.exceptionOrNull() is SocketClosedException,
                    "client $i: the server closed but its connection stayed open: $outcome",
                )
                client.close()
            }
        }

    private companion object {
        const val ROUNDS = 2000
        const val CALLBACK_THREADS = 4
    }
}
