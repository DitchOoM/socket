package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.DatagramIngress
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.sim.fixtures.SIM_IDLE_TIMEOUT
import com.ditchoom.socket.quic.sim.fixtures.walk20260910Conn1DeadLinkHandoff
import com.ditchoom.socket.quic.sim.fixtures.walk20260912Conn7DeadLinkHandoff
import com.ditchoom.socket.quic.sim.fixtures.walk20260920Leg3Conn1DownlinkBlackout
import com.ditchoom.socket.quic.sim.fixtures.walk20260924V4Conn1CellularNotYetPassing
import com.ditchoom.socket.quic.sim.fixtures.walk20260924V4Conn2AbruptHandoff
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The input-event subset of recorded walk connections, replayed against the driver.
 *
 * Four are failures, each windowed from the instant of the last datagram the connection ever
 * received. Two are dead-link handoffs: the platform reports cellular, every send on the old Wi-Fi
 * socket fails `errno=57` for the rest of the connection's life (94 faults on the 2026-09-12 walk's
 * connection 7, 45 on the 2026-09-10 walk's connection 1), and nothing arrives on any path. The
 * others are a downlink blackout and a cellular link that came up but never passed traffic. The
 * fifth is the positive control: an abrupt Wi-Fi to cellular handoff that re-homed the connection.
 *
 * Tier A sees the driver, not quiche's connection-id pool (RFC_DETERMINISTIC_SIMULATION.md §4), so
 * what these pin is the driver-visible half of the walks: a dead socket's send faults cost the
 * connection nothing, the link change is observed, and a failure ends on its own idle deadline with
 * a typed reason, 30 s after the last datagram in, as every phone recorded it. The path half (the
 * probes, the connection-id pool, which direction was lost) is `MigrationSimTestSuite`'s walk
 * scenarios, against real quiche.
 */
class WalkFixtureTests {
    private suspend fun TestScope.runWalk(fixture: SimFixture): QuicSimRun =
        runQuicSim(fixture, keepAliveInterval = null, ingress = DatagramIngress.DriverReaderLoop) {
            connTimeout = SIM_IDLE_TIMEOUT
            closeOnTimeout = true
        }

    private fun QuicSimRun.assertDiesOnItsOwnDeadline(fixture: SimFixture) {
        val closes = trace.events.filterIsInstance<Observed.StateChange>().filter { it.state is QuicConnectionState.Closed }
        val early = closes.firstOrNull { it.at < SIM_IDLE_TIMEOUT }
        assertNull(early, "the connection ended at ${early?.at}, before its idle deadline: a send fault on the dead socket was terminal")
        val close = closes.first()
        assertEquals(SIM_IDLE_TIMEOUT, close.at, "the close is not on the idle deadline")
        val reason = assertIs<QuicConnectionState.Closed>(close.state).reason
        assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), reason, "the walk recorded `local: IdleTimeout`")
        val cellular =
            trace.events.filterIsInstance<Observed.NetworkChanged>().firstOrNull {
                val state = it.state
                state is NetworkState.Routable && (state.id as? NetworkId.Link)?.kind == NetworkKind.Cellular
            }
        assertTrue(cellular != null && cellular.at < close.at, "the link change the phone reported was never observed: ${trace.events}")
        assertTrue(trace.events.any { it is Observed.DatagramFed }, "the last datagram the phone received was not fed")
        assertTrue(fixture.duration >= SIM_IDLE_TIMEOUT, "the recorded window ends before the deadline it is meant to show")
    }

    @Test
    fun walk20260912Conn7_deadSocketFaultsNeverEndTheConnectionBeforeItsIdleDeadline() =
        runTest { runWalk(walk20260912Conn7DeadLinkHandoff).assertDiesOnItsOwnDeadline(walk20260912Conn7DeadLinkHandoff) }

    @Test
    fun walk20260910Conn1_deadSocketFaultsNeverEndTheConnectionBeforeItsIdleDeadline() =
        runTest { runWalk(walk20260910Conn1DeadLinkHandoff).assertDiesOnItsOwnDeadline(walk20260910Conn1DeadLinkHandoff) }

    /**
     * 2026-09-20 iPhone walk, leg 3, connection 1: one datagram in, then 30 s in which the server's
     * answer to every probe was lost on the way down (`MigrationSimTestSuite` replays that half
     * against real quiche). Cellular is reported a second into the blackout, the old socket's sends
     * fail now and then, and nothing more arrives.
     */
    @Test
    fun walk20260920Leg3Conn1_aDownlinkBlackoutEndsOnTheIdleDeadline() =
        runTest {
            val run = runWalk(walk20260920Leg3Conn1DownlinkBlackout)
            run.assertDiesOnItsOwnDeadline(walk20260920Leg3Conn1DownlinkBlackout)
            assertEquals(1, run.trace.events.count { it is Observed.DatagramFed }, "nothing reached the phone after the blackout began")
        }

    /**
     * 2026-09-24 Samsung walk, lane v4, connection 1: Wi-Fi goes dark after the connection's last
     * datagram in, its sends fail `destination unreachable` from 10 s on, the OS reports `Offline` at
     * 15.1 s and at 15.4 s a cellular link that is up but has not yet passed traffic, and the
     * connection ends on its idle deadline. Neither `Offline` nor the send faults end it sooner.
     */
    @Test
    fun walk20260924V4Conn1_anOfflineReportAndALinkNotYetPassingEndOnTheIdleDeadline() =
        runTest {
            val run = runWalk(walk20260924V4Conn1CellularNotYetPassing)
            run.assertDiesOnItsOwnDeadline(walk20260924V4Conn1CellularNotYetPassing)
            run.trace.assertSequence {
                anyTime("the OS reports Offline") { it is Observed.NetworkChanged && it.state == NetworkState.Offline }
                anyTime("then a cellular link that has not yet passed traffic") {
                    it is Observed.NetworkChanged && (it.state as? NetworkState.Routable)?.internet == InternetAccess.Observed.Pending
                }
                at(SIM_IDLE_TIMEOUT, "then the idle close") { it is Observed.StateChange && it.state is QuicConnectionState.Closed }
            }
        }

    /**
     * 2026-09-24 Samsung walk, lane v4, connection 2: the positive control. Wi-Fi's downlink went
     * silent 10.9 s before the OS reported `Offline`; 0.2 s later cellular was reported, and the first
     * datagram over it arrived 0.5 s after that. The driver carries the connection through: no close
     * across the silence or the send faults, every datagram fed, traffic flowing again on the new link.
     */
    @Test
    fun walk20260924V4Conn2_anAbruptHandoffThatReHomedKeepsTheConnectionUp() =
        runTest {
            val fixture = walk20260924V4Conn2AbruptHandoff
            val run = runWalk(fixture)
            // The run's own teardown closes the driver at the window's end, which the trace's
            // whole-millisecond clock stamps at the fixture's duration truncated; before that is the replay's.
            val teardown = fixture.duration.inWholeMilliseconds.milliseconds
            val states =
                run.trace.events
                    .filterIsInstance<Observed.StateChange>()
                    .filter { it.at < teardown }
            assertEquals(emptyList(), states.filter { it.state is QuicConnectionState.Closed }, "the connection re-homed in the field")
            assertIs<QuicConnectionState.Established>(states.last().state, "the connection must be up at the end of the window")
            val fed = run.trace.events.filterIsInstance<Observed.DatagramFed>()
            assertEquals(fixture.events.count { it is SimEvent.DatagramIn }, fed.size, "a datagram the phone received was not fed")
            val cellular =
                run.trace.events.filterIsInstance<Observed.NetworkChanged>().first {
                    ((it.state as? NetworkState.Routable)?.id as? NetworkId.Link)?.kind == NetworkKind.Cellular
                }
            assertTrue(fed.any { it.at > cellular.at }, "no datagram arrived after the handoff: ${run.trace.render()}")
        }

    @Test
    fun everyWalk_deterministic3x() =
        runTest {
            for (fixture in listOf(
                walk20260912Conn7DeadLinkHandoff,
                walk20260910Conn1DeadLinkHandoff,
                walk20260920Leg3Conn1DownlinkBlackout,
                walk20260924V4Conn1CellularNotYetPassing,
                walk20260924V4Conn2AbruptHandoff,
            )) {
                val golden = runWalk(fixture).trace.events
                repeat(3) { assertEquals(golden, runWalk(fixture).trace.events, "${fixture.name} is not deterministic") }
            }
        }
}
