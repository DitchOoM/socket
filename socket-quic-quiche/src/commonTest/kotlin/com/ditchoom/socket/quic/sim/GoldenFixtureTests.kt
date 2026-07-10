package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.sim.fixtures.SIM_IDLE_TIMEOUT
import com.ditchoom.socket.quic.sim.fixtures.SIM_KEEPALIVE_INTERVAL
import com.ditchoom.socket.quic.sim.fixtures.datagramThenStalePath
import com.ditchoom.socket.quic.sim.fixtures.idleTimeoutClose
import com.ditchoom.socket.quic.sim.fixtures.keepaliveIdleSurvival
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
            timedOut = true
        }

    private val keepaliveIdleSurvivalGolden =
        listOf<Observed>(
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Handshaking),
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Established("h3")),
            Observed.KeepAlivePing(10.seconds),
            Observed.KeepAlivePing(20.seconds),
            Observed.KeepAlivePing(30.seconds),
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
            timedOut = true // quiche reports the close as an idle timeout, not a clean shutdown
        }

    private val idleTimeoutCloseGolden =
        listOf<Observed>(
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Handshaking),
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Established("h3")),
            Observed.StateChange(30.seconds, QuicConnectionState.Closed(QuicError.IdleTimeout)),
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
            Observed.StateChange(Duration.ZERO, QuicConnectionState.Established("h3")),
            Observed.DatagramFed(Duration.ZERO, 8),
            Observed.NetworkChanged(3.seconds, NetworkId.KindOnly(NetworkKind.Cellular)),
            Observed.DatagramFed(3.seconds + 5.milliseconds, 6),
        )

    @Test
    fun datagramThenStalePath_stalePacketFedAndConnectionSurvives() =
        runTest {
            val run = runDatagramThenStalePath()
            run.trace.assertMatches(datagramThenStalePathGolden)
            // The reconnect-race shape: the post-path-change datagram is data, not a teardown
            // signal — the trace must show it fed to quiche with no Closed transition anywhere.
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
}
