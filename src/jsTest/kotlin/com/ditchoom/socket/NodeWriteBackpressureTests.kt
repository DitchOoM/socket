package com.ditchoom.socket

import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.harness.NonDrainingPeer
import com.ditchoom.socket.harness.WriteOutcome
import com.ditchoom.socket.harness.writeOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Node-specific write-timeout contract check (RFC_WRITE_TIMEOUT_CONTRACT.md), standing in for the
 * common [com.ditchoom.socket.harness.WriteTimeoutContractTests], which skip Node
 * ([nonDrainingPeerIsReliable] = false): our own `ServerSocket` attaches a `'data'` listener on accept,
 * putting the accepted Node socket into flowing mode, so it always drains — the client never
 * back-pressures.
 *
 * A **raw** `net` server whose accepted sockets get *no* `'data'` listener stays in Node's default
 * paused mode (a socket reads nothing until a `'data'` listener is added or `resume()` is called), so
 * the OS receive buffer fills and the client's writes genuinely back-pressure. That lets us prove the
 * two Node behaviors that matter:
 *  - the `UntilClosed`/infinite default **suspends** on back-pressure (RFC §1);
 *  - an opt-in `Bounded(d)` write **throws [SocketTimeoutException] and auto-closes** (RFC §2–§4).
 */
class NodeWriteBackpressureTests {
    private val deadline = 1.seconds
    private val watchdog = 6.seconds
    private val suspendObservation = 3.seconds

    private fun clientConfig(policy: WritePolicy) =
        TransportConfig(
            writePolicy = policy,
            connectTimeout = 5.seconds,
            io = IoTuning(sendBuffer = NonDrainingPeer.SMALL_SOCKET_BUFFER),
        )

    /** Starts a raw `net` server that never reads its accepted sockets; returns it with its bound port. */
    private suspend fun startPausedPeer(): Pair<Server, Int> {
        val portReady = CompletableDeferred<Int>()
        val server =
            Net.createServer { socket ->
                // Hold the connection but attach no 'data' listener, so it stays paused and never drains.
                // Swallow errors so a reset during teardown doesn't crash the process.
                socket.on("error") { _ -> }
            }
        server.listen(port = 0, host = "127.0.0.1") {
            portReady.complete(server.address()!!.port)
        }
        return server to portReady.await()
    }

    @Test
    fun untilClosedWriteToPausedPeerSuspends() =
        runTestNoTimeSkipping {
            val (server, port) = startPausedPeer()
            val client = ClientSocket.connect(port, config = clientConfig(WritePolicy.UntilClosed))
            try {
                val outcome = client.writeOutcome(deadline = Duration.INFINITE, watchdog = suspendObservation)
                assertTrue(
                    outcome is WriteOutcome.WatchdogExpired,
                    "UntilClosed write to a paused Node peer did not suspend on back-pressure (RFC write §1). " +
                        "Outcome: $outcome",
                )
            } finally {
                client.close()
                server.close {}
            }
        }

    @Test
    fun boundedWriteToPausedPeerTimesOutAndCloses() =
        runTestNoTimeSkipping {
            val (server, port) = startPausedPeer()
            val client = ClientSocket.connect(port, config = clientConfig(WritePolicy.Bounded(deadline)))
            try {
                val outcome = client.writeOutcome(deadline, watchdog)
                assertTrue(
                    outcome is WriteOutcome.Threw,
                    "Bounded($deadline) write to a paused Node peer did not time out (RFC write §2). Outcome: $outcome",
                )
                assertTrue(
                    outcome.error is SocketTimeoutException,
                    "write-timeout surfaced as ${outcome.error::class.simpleName}, not SocketTimeoutException " +
                        "(RFC write §3). Error: ${outcome.error}",
                )
                assertFalse(
                    client.isOpen,
                    "connection stayed open after a bounded write-timeout — it must be destructive (RFC write §4)",
                )
            } finally {
                client.close()
                server.close {}
            }
        }
}
