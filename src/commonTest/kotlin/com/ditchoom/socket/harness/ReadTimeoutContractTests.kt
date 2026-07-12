package com.ditchoom.socket.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.data.readBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.TransportKind
import com.ditchoom.socket.connect
import com.ditchoom.socket.networkCapabilities
import com.ditchoom.socket.runTestNoTimeSkipping
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The `read(deadline)` contract conformance matrix from `RFC_READ_TIMEOUT_CONTRACT.md` §6.
 *
 * These five assertions encode the **proposed contract** (§3), not today's behavior. They run
 * unchanged on every platform against the in-process [SilentPeer] (accept-then-silence) fixture,
 * so a failure is a *provable* per-platform divergence. Current expected colors against each
 * platform's **default** implementation (**Phases 2 + 4a + 4b landed** — all platforms conformant):
 *
 * | Assertion (contract §3)                              | Axis | JVM (NIO2 default) | Node | Apple | Linux |
 * |------------------------------------------------------|------|--------------------|------|-------|-------|
 * | [boundedReadOfSilentPeerTimesOut]                    | 1    | green              | green| green | green |
 * | [readTimeoutThrowsSocketTimeoutException]            | 3    | green ✅ (was IOException) | green ✅ (was TCE) | green ✅ (was TCE) | green |
 * | [connectionSurvivesReadTimeoutForReading]            | 2    | green ✅ (was destructive) | green| green ✅ (was destructive) | green |
 * | [untilClosedReadOfSilentPeerDoesNotTimeOut]          | opt-out | green           | green| green | green |
 * | [connectionSurvivesReadTimeoutForWriting]            | 2    | green              | green| green | green |
 *
 * The JVM *blocking* and *selector* variants are not the JVM default, so they're exercised
 * separately in `JvmReadTimeoutVariantTests` (commonJvmTest) — that's where the Axis-1 enforcement
 * RED (blocking path hangs → [ReadOutcome.WatchdogExpired]) was visible before Phase 3. **Phase 2 also
 * made the selector path fully conformant** (it previously leaked a `TimeoutCancellationException`).
 *
 * **Phase 4a** made JVM-NIO2 non-destructive via the orphaned-read single-flight
 * (`AsyncBaseClientSocket.orphanedReadRaw`, RFC §3.2); **Phase 4b** did the same for Apple
 * (`NWSocketWrapper.readRaw` captures the outstanding native receive in a `CompletableDeferred`,
 * `closeInternal` only on real EOF/error/close — macOS-CI-validated). With 4b the whole matrix is
 * green. **Do not "fix" a regression here by weakening the assertion.** Linux is the reference impl
 * (§3.1).
 */
class ReadTimeoutContractTests {
    private val deadline = 1.seconds

    /** Watchdog for a bounded read: well above [deadline] so an enforcing platform fires first. */
    private val watchdog = 6.seconds

    private fun tcpAvailable(): Boolean = networkCapabilities().transports.contains(TransportKind.TCP)

    private fun boundedConfig() =
        TransportConfig(
            readPolicy = ReadPolicy.Bounded(deadline),
            connectTimeout = 5.seconds,
        )

    /**
     * Axis 1 (enforcement): a `Bounded(d)` read of a peer that sends nothing terminates by throwing
     * within `~d`, rather than blocking indefinitely.
     */
    @Test
    fun boundedReadOfSilentPeerTimesOut() =
        runTestNoTimeSkipping {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val peer = SilentPeer.start()
            val client = ClientSocket.connect(peer.port, config = boundedConfig())
            try {
                val outcome = client.readOutcome(deadline, watchdog)
                assertTrue(
                    outcome is ReadOutcome.Threw,
                    "Bounded($deadline) read of a silent peer did not time out — deadline not enforced " +
                        "(RFC Axis 1). Outcome: $outcome",
                )
                assertTrue(
                    outcome.elapsed < deadline + 3.seconds,
                    "read timed out but far past the deadline (${outcome.elapsed} vs $deadline)",
                )
            } finally {
                client.close()
                peer.close()
            }
        }

    /**
     * Axis 3 (uniform type): the read-timeout throws [SocketTimeoutException], never a
     * `SocketIOException` (JVM default) nor a bare kotlinx `TimeoutCancellationException` (Apple/Node).
     */
    @Test
    fun readTimeoutThrowsSocketTimeoutException() =
        runTestNoTimeSkipping {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val peer = SilentPeer.start()
            val client = ClientSocket.connect(peer.port, config = boundedConfig())
            try {
                val outcome = client.readOutcome(deadline, watchdog)
                assertTrue(
                    outcome is ReadOutcome.Threw,
                    "read did not throw on timeout; outcome: $outcome",
                )
                assertTrue(
                    outcome.error is SocketTimeoutException,
                    "read-timeout surfaced as ${outcome.error::class.simpleName}, not SocketTimeoutException " +
                        "(RFC Axis 3). Error: ${outcome.error}",
                )
            } finally {
                client.close()
                peer.close()
            }
        }

    /**
     * Axis 2 (non-destructive, read half): after a read times out, the connection stays open and a
     * subsequent read observes data the peer finally sends. A timeout is not a close.
     */
    @Test
    fun connectionSurvivesReadTimeoutForReading() =
        runTestNoTimeSkipping {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val peer = SilentPeer.start()
            val client = ClientSocket.connect(peer.port, config = boundedConfig())
            try {
                // First read times out (peer is silent).
                val first = client.readOutcome(deadline, watchdog)
                assertTrue(first is ReadOutcome.Threw, "precondition: first read should time out; got $first")

                // Peer breaks its silence; a live connection must let us read it.
                val payload = "resurrected-after-timeout"
                peer.send(payload)
                val second =
                    withTimeoutOrNull(watchdog) {
                        runCatching { client.readBuffer(5.seconds) }
                    }
                assertNotNull(second, "second read after a read-timeout hung — connection unusable (RFC Axis 2)")
                val buffer =
                    second.getOrElse {
                        fail(
                            "second read after a read-timeout threw ${it::class.simpleName} — the timeout was " +
                                "destructive (RFC Axis 2). Error: $it",
                        )
                    }
                val received = buffer.readString(buffer.remaining(), Charset.UTF8)
                assertEquals(payload, received, "second read returned unexpected data")
            } finally {
                client.close()
                peer.close()
            }
        }

    /**
     * Enforcement opt-out: an `UntilClosed` (infinite-deadline) read of a silent peer does **not**
     * time out — it stays parked (observed here as [ReadOutcome.WatchdogExpired]) rather than
     * throwing a [SocketTimeoutException] on some inherited default.
     */
    @Test
    fun untilClosedReadOfSilentPeerDoesNotTimeOut() =
        runTestNoTimeSkipping {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val peer = SilentPeer.start()
            val client =
                ClientSocket.connect(
                    peer.port,
                    config = TransportConfig(readPolicy = ReadPolicy.UntilClosed, connectTimeout = 5.seconds),
                )
            try {
                // Infinite deadline: over a bounded observation window it must remain parked.
                val observationWindow = 2.seconds
                val outcome = client.readOutcome(deadline = Duration.INFINITE, watchdog = observationWindow)
                assertTrue(
                    outcome is ReadOutcome.WatchdogExpired,
                    "UntilClosed read of a silent peer did not stay parked — the enforcement opt-out was " +
                        "not honored. Outcome: $outcome",
                )
            } finally {
                client.close()
                peer.close()
            }
        }

    /**
     * Axis 2 (non-destructive, write half): non-destructiveness extends to the write side — a write
     * issued after a read timed out succeeds on the still-open connection.
     */
    @Test
    fun connectionSurvivesReadTimeoutForWriting() =
        runTestNoTimeSkipping {
            if (!tcpAvailable()) return@runTestNoTimeSkipping
            val peer = SilentPeer.start()
            val client = ClientSocket.connect(peer.port, config = boundedConfig())
            try {
                val first = client.readOutcome(deadline, watchdog)
                assertTrue(first is ReadOutcome.Threw, "precondition: first read should time out; got $first")

                val wrote =
                    withTimeoutOrNull(watchdog) {
                        runCatching { client.write("ping-after-timeout".toReadBuffer(Charset.UTF8), 3.seconds) }
                    }
                assertNotNull(wrote, "write after a read-timeout hung — connection unusable (RFC Axis 2)")
                wrote.getOrElse {
                    fail(
                        "write after a read-timeout threw ${it::class.simpleName} — the read timeout was " +
                            "destructive to the write half (RFC Axis 2). Error: $it",
                    )
                }
            } finally {
                client.close()
                peer.close()
            }
        }
}
