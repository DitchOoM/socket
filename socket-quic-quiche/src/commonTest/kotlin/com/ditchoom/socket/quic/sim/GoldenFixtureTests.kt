package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.sim.fixtures.SIM_IDLE_TIMEOUT
import com.ditchoom.socket.quic.sim.fixtures.SIM_KEEPALIVE_INTERVAL
import com.ditchoom.socket.quic.sim.fixtures.datagramThenStalePath
import com.ditchoom.socket.quic.sim.fixtures.idleTimeoutClose
import com.ditchoom.socket.quic.sim.fixtures.keepaliveIdleSurvival
import com.ditchoom.socket.quic.sim.fixtures.sendFaultSurvival
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The W2 golden fixtures of the quiche-driver tier, replayed through the [SimTimeline] engine and
 * asserted against hand-written golden traces ([SimTrace.assertMatches]) plus, for each fixture, a
 * 50× determinism loop: identical [SimTrace] on every iteration inside one `runTest`.
 *
 * Multi-minute virtual timelines; each test's wall-clock is milliseconds — no real-time sleeps
 * anywhere (the [SimClock] acceptance bar).
 */
class GoldenFixtureTests {
    // ---- golden 1: keepalive-idle-survival ----

    private suspend fun TestScope.runKeepaliveIdleSurvival(): QuicSimRun =
        runQuicSim(keepaliveIdleSurvival, keepAliveInterval = SIM_KEEPALIVE_INTERVAL) {
            // Idle timer armed and lethal: if any of the three keepalive deadlines failed to fire
            // first, the fire would go to quiche and idle-close the connection — the golden trace
            // would then show the Closed transition instead of the third PING.
            connTimeout = SIM_IDLE_TIMEOUT
            closeOnTimeout = true
        }

    private val keepaliveIdleSurvivalGolden =
        listOf<Observed>(
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Handshaking),
            // Established carries the REAL negotiated ALPN read from the backend (quiche_conn_application_proto);
            // the scripted sim api models no ALPN, so the driver reports the empty string here.
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Established("")),
            Observed.KeepAlivePing(10.seconds),
            Observed.KeepAlivePing(20.seconds),
            Observed.KeepAlivePing(30.seconds),
            // End of life, at the end of the timeline: the driver publishes its terminal state
            // whatever unwinds the loop, so a recorded session always ends with a typed reason instead
            // of trailing off on Established. [QuicCloseReason.Unspecified] is the honest one here —
            // the harness tore the run down at its horizon, with no CONNECTION_CLOSE and no timeout.
            //
            // This used to read `Closed(IdleTimeout)` with a comment explaining that the connection had
            // NOT actually idled out and only looked that way because the fixture pre-armed
            // `timedOut = true`. A golden needing a disclaimer to be read correctly is recording the
            // wrong thing; the stub now raises `timedOut` only when the idle timer really fires, so the
            // trace says what happened. The three PINGs and `onTimeoutCount == 0` still prove survival.
            Observed.StateChange(30.seconds, QuicConnectionState.Closed(QuicCloseReason.Unspecified)),
        )

    @Test
    fun keepaliveIdleSurvival_exactlyThreePings_staysEstablished() =
        runTest {
            val run = runKeepaliveIdleSurvival()
            run.trace.assertMatches(keepaliveIdleSurvivalGolden)
            assertEquals(3, run.api.ackElicitingCount, "exactly one PING per elapsed keepalive interval")
            assertEquals(0, run.api.onTimeoutCount, "the idle timer must never be handed to quiche")
        }

    @Test
    fun keepaliveIdleSurvival_deterministic50x() =
        runTest {
            repeat(50) {
                runKeepaliveIdleSurvival().trace.assertMatches(keepaliveIdleSurvivalGolden)
            }
        }

    // ---- golden 2: idle-timeout-close ----

    private suspend fun TestScope.runIdleTimeoutClose(): QuicSimRun =
        runQuicSim(idleTimeoutClose, keepAliveInterval = null) {
            connTimeout = SIM_IDLE_TIMEOUT
            closeOnTimeout = true
        }

    private val idleTimeoutCloseGolden =
        listOf<Observed>(
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Handshaking),
            // Established carries the REAL negotiated ALPN read from the backend (quiche_conn_application_proto);
            // the scripted sim api models no ALPN, so the driver reports the empty string here.
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Established("")),
            Observed.StateChange(30.seconds, QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.IdleTimeout))),
            Observed.ErrorSurfaced(30.seconds, QuicError.IdleTimeout),
        )

    @Test
    fun idleTimeoutClose_typedIdleTimeoutAtExactInstant() =
        runTest {
            val run = runIdleTimeoutClose()
            run.trace.assertMatches(idleTimeoutCloseGolden)
            assertEquals(1, run.api.onTimeoutCount, "the idle fire is handed to quiche exactly once")
            assertEquals(0, run.api.ackElicitingCount, "keepalive disabled — no PINGs")
        }

    @Test
    fun idleTimeoutClose_deterministic50x() =
        runTest {
            repeat(50) {
                runIdleTimeoutClose().trace.assertMatches(idleTimeoutCloseGolden)
            }
        }

    // ---- golden 4: datagram-then-stale-path (clientMode = true: real reader loop) ----

    private suspend fun TestScope.runDatagramThenStalePath(): QuicSimRun =
        runQuicSim(datagramThenStalePath, keepAliveInterval = null, clientMode = true)

    private val datagramThenStalePathGolden =
        listOf<Observed>(
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Handshaking),
            // Established carries the REAL negotiated ALPN read from the backend (quiche_conn_application_proto);
            // the scripted sim api models no ALPN, so the driver reports the empty string here.
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Established("")),
            Observed.DatagramFed(Duration.ZERO, 8),
            Observed.NetworkChanged(
                3.seconds,
                NetworkState.Routable(NetworkId.KindOnly(NetworkKind.Cellular), InternetAccess.Unobserved),
            ),
            Observed.DatagramFed(3.seconds + 5.milliseconds, 6),
            // End of life at the end of the timeline: the stale-path packet was data, not a teardown
            // signal, so the connection reached t=4s alive. The reason is [QuicCloseReason.Unspecified]
            // because the harness tears the run down at its horizon — no CONNECTION_CLOSE is exchanged
            // and nothing times out. That is deliberately NOT `Graceful`: this connection never shut
            // down through the protocol, and the old nullable's `null` claimed otherwise. A
            // `ByPeer`/`ByLocal` here — or any Closed before t=4s — would be the regression.
            Observed.StateChange(4.seconds, QuicConnectionState.Closed(QuicCloseReason.Unspecified)),
        )

    @Test
    fun datagramThenStalePath_stalePacketFedAndConnectionSurvives() =
        runTest {
            val run = runDatagramThenStalePath()
            run.trace.assertMatches(datagramThenStalePathGolden)
            // The reconnect-race shape: the post-path-change datagram is data, not a teardown
            // signal — the trace must show it fed to quiche with no Closed transition before the
            // end-of-timeline teardown (the golden above pins that one at t=4s, error-free).
            run.trace.assertSequence {
                at(3.seconds + 5.milliseconds, "stale-path datagram fed to quiche") {
                    it is Observed.DatagramFed && it.len == 6
                }
            }
        }

    @Test
    fun datagramThenStalePath_deterministic50x() =
        runTest {
            repeat(50) {
                runDatagramThenStalePath().trace.assertMatches(datagramThenStalePathGolden)
            }
        }

    // ---- golden 5: send-fault-survival (the shrunk fuzz counterexample) ----

    private suspend fun TestScope.runSendFaultSurvival(): QuicSimRun =
        runQuicSim(sendFaultSurvival, keepAliveInterval = SIM_KEEPALIVE_INTERVAL, clientMode = true) {
            connTimeout = SIM_IDLE_TIMEOUT
            closeOnTimeout = true
            // Same send-pressure model the fuzz harness uses: a PING arms one outbound datagram, so
            // the armed fault has a real send to land on.
            onAckEliciting = { connSendOnce = 1200 }
        }

    @Test
    fun sendFaultSurvival_connectionSurvivesTheFaultAndKeepsPinging() =
        runTest {
            val run = runSendFaultSurvival()

            // Bound on the fixture horizon, exactly as fuzz invariant 6 does: runQuicSim tears the
            // driver down when the timeline ends, and that teardown records a terminal Closed. Counting
            // it would make every fixture look like a failure.
            val closedDuringTimeline =
                run.trace.events
                    .filterIsInstance<Observed.StateChange>()
                    .firstOrNull { it.state is QuicConnectionState.Closed && it.at < sendFaultSurvival.duration }
            assertNull(
                closedDuringTimeline,
                "a single transport send fault ended the connection at ${closedDuringTimeline?.at} — " +
                    "one undelivered datagram must not cost the session",
            )
            assertEquals(
                0,
                run.api.onTimeoutCount,
                "no quiche idle timer should have fired within the horizon, so any close would be the send fault",
            )
            assertTrue(
                run.api.ackElicitingCount >= 2,
                "keepalive stopped after the fault (${run.api.ackElicitingCount} PINGs) — the driver " +
                    "stopped making progress even though only one datagram failed",
            )
        }

    @Test
    fun sendFaultSurvival_deterministic50x() =
        runTest {
            val golden = runSendFaultSurvival().trace.events
            repeat(50) {
                assertEquals(golden, runSendFaultSurvival().trace.events, "send-fault-survival is not deterministic")
            }
        }
}
