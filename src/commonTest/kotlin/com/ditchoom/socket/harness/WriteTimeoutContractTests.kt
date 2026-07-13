package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.IoTuning
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.TransportKind
import com.ditchoom.socket.connect
import com.ditchoom.socket.networkCapabilities
import com.ditchoom.socket.nonDrainingPeerIsReliable
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The `write(deadline)` contract conformance matrix from `RFC_WRITE_TIMEOUT_CONTRACT.md`.
 *
 * The write contract is deliberately **asymmetric** to the read contract (`RFC_READ_TIMEOUT_CONTRACT.md`),
 * because a stalled write is *back-pressure* (connection-global TCP flow control), not the per-stream
 * "no data yet" of a stalled read:
 *
 *  1. **Default = suspend / back-pressure.** The shipped default [WritePolicy.UntilClosed] (infinite
 *     deadline) makes a write to a peer that never drains *suspend*, not fail — a blocked writer is
 *     normal flow control, and killing a usable connection over transient back-pressure is wrong.
 *  2. **`Bounded(d)` is enforced.** A caller that opts into a write deadline gets it: the write throws
 *     within `~d` rather than suspending forever.
 *  3. **`Bounded(d)` timeout is uniform** [SocketTimeoutException] (never a bare kotlinx
 *     `TimeoutCancellationException`, an errno string, or a hang).
 *  4. **`Bounded(d)` timeout is DESTRUCTIVE (auto-close).** Opposite of the read contract: when a
 *     *bounded* write blows its deadline the send buffer is wedged and the connection's write capacity
 *     is gone, so the connection is closed. This is the one place a write-timeout kills the connection,
 *     and it is opt-in — the default never does.
 *
 * These assertions run unchanged on every platform against the in-process [NonDrainingPeer]
 * (accept-then-never-read) fixture, so a failure is a *provable* per-platform divergence. The JVM
 * *blocking* and *selector* variants are exercised separately in `JvmWriteTimeoutVariantTests`
 * (commonJvmTest) — that is where the blocking path's Axis-2 hang (deadline dropped) is provable, since
 * blocking is never the default.
 */
class WriteTimeoutContractTests {
    private val deadline = 1.seconds

    /** Watchdog for a bounded write: well above [deadline] so an enforcing platform fires first. */
    private val watchdog = 6.seconds

    /** Observation window for the suspend (infinite-deadline) case: short — we assert it stays parked. */
    private val suspendObservation = 3.seconds

    private fun tcpAvailable(): Boolean = networkCapabilities().transports.contains(TransportKind.TCP)

    private fun clientIo() = IoTuning(sendBuffer = NonDrainingPeer.SMALL_SOCKET_BUFFER)

    private fun boundedConfig() =
        TransportConfig(
            writePolicy = WritePolicy.Bounded(deadline),
            connectTimeout = 5.seconds,
            io = clientIo(),
        )

    private fun suspendConfig() =
        TransportConfig(
            writePolicy = WritePolicy.UntilClosed,
            connectTimeout = 5.seconds,
            io = clientIo(),
        )

    /**
     * Contract §1 (default back-pressures by suspending): an [WritePolicy.UntilClosed] (infinite
     * deadline) write to a peer that never drains **suspends** — observed as [WriteOutcome.WatchdogExpired]
     * — rather than throwing. A blocked writer is flow control, not an error, and the connection is not
     * killed. (A path that instead acknowledges never-read bytes yields [WriteOutcome.CompletedWithoutBackpressure].)
     */
    @Test
    fun untilClosedWriteToNonDrainingPeerSuspends() =
        runTestNoTimeSkipping {
            if (!tcpAvailable() || !nonDrainingPeerIsReliable()) return@runTestNoTimeSkipping
            val peer = NonDrainingPeer.start()
            val client = ClientSocket.connect(peer.port, config = suspendConfig())
            try {
                peer.awaitAccepted()
                val outcome = client.writeOutcome(deadline = kotlin.time.Duration.INFINITE, watchdog = suspendObservation)
                assertTrue(
                    outcome is WriteOutcome.WatchdogExpired,
                    "UntilClosed write to a non-draining peer did not suspend on back-pressure — it should " +
                        "park, not fail (RFC write §1). Outcome: $outcome",
                )
            } finally {
                client.close()
                peer.close()
            }
        }

    /**
     * Contract §2 (enforcement): a `Bounded(d)` write to a peer that never drains terminates by throwing
     * within `~d`, rather than back-pressuring indefinitely.
     */
    @Test
    fun boundedWriteToNonDrainingPeerTimesOut() =
        runTestNoTimeSkipping {
            if (!tcpAvailable() || !nonDrainingPeerIsReliable()) return@runTestNoTimeSkipping
            val peer = NonDrainingPeer.start()
            val client = ClientSocket.connect(peer.port, config = boundedConfig())
            try {
                peer.awaitAccepted()
                val outcome = client.writeOutcome(deadline, watchdog)
                assertTrue(
                    outcome is WriteOutcome.Threw,
                    "Bounded($deadline) write to a non-draining peer did not time out — deadline not enforced " +
                        "(RFC write §2). Outcome: $outcome",
                )
                assertTrue(
                    outcome.elapsed < deadline + 3.seconds,
                    "write timed out but far past the deadline (${outcome.elapsed} vs $deadline)",
                )
            } finally {
                client.close()
                peer.close()
            }
        }

    /**
     * Contract §3 (uniform type): the bounded-write timeout throws [SocketTimeoutException], never a bare
     * kotlinx `TimeoutCancellationException` (Apple/Node), an errno-string exception, nor an
     * `IllegalState`/hang.
     */
    @Test
    fun boundedWriteTimeoutThrowsSocketTimeoutException() =
        runTestNoTimeSkipping {
            if (!tcpAvailable() || !nonDrainingPeerIsReliable()) return@runTestNoTimeSkipping
            val peer = NonDrainingPeer.start()
            val client = ClientSocket.connect(peer.port, config = boundedConfig())
            try {
                peer.awaitAccepted()
                val outcome = client.writeOutcome(deadline, watchdog)
                assertTrue(
                    outcome is WriteOutcome.Threw,
                    "write did not throw on timeout; outcome: $outcome",
                )
                assertTrue(
                    outcome.error is SocketTimeoutException,
                    "write-timeout surfaced as ${outcome.error::class.simpleName}, not SocketTimeoutException " +
                        "(RFC write §3). Error: ${outcome.error}",
                )
            } finally {
                client.close()
                peer.close()
            }
        }

    /**
     * Contract §4 (destructive — the deliberate asymmetry with reads): after a `Bounded` write times out,
     * the connection is **closed**. `isOpen` is false and a subsequent write fails with
     * [SocketClosedException] — the timeout wedged the send buffer and killed the write half, so the
     * connection is not handed back as usable (unlike a read timeout, which is non-destructive).
     */
    @Test
    fun boundedWriteTimeoutClosesConnection() =
        runTestNoTimeSkipping {
            if (!tcpAvailable() || !nonDrainingPeerIsReliable()) return@runTestNoTimeSkipping
            val peer = NonDrainingPeer.start()
            val client = ClientSocket.connect(peer.port, config = boundedConfig())
            try {
                peer.awaitAccepted()
                val first = client.writeOutcome(deadline, watchdog)
                assertTrue(first is WriteOutcome.Threw, "precondition: first bounded write should time out; got $first")

                assertFalse(
                    client.isOpen,
                    "connection stayed open after a bounded write-timeout — the timeout must be destructive " +
                        "(auto-close) for writes (RFC write §4)",
                )
                val second =
                    runCatching {
                        client.write("after-write-timeout".toReadBuffer(Charset.UTF8), deadline)
                    }
                assertTrue(
                    second.exceptionOrNull() is SocketClosedException,
                    "a write after a destructive write-timeout should throw SocketClosedException, not " +
                        "${second.exceptionOrNull()?.let { it::class.simpleName } ?: "succeed"} (RFC write §4)",
                )
            } finally {
                client.close()
                peer.close()
            }
        }
}
