package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.DatagramIngress
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.sim.fixtures.SIM_IDLE_TIMEOUT
import com.ditchoom.socket.quic.sim.fixtures.walk20260912Conn7DeadLinkHandoff
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The input-event subset of two recorded dead-link handoffs, from the instant of the last datagram the
 * connection ever received: the platform reports cellular, every send on the old Wi-Fi socket fails
 * `errno=57` for the rest of the connection's life (94 faults on the 2026-09-12 walk's connection 7,
 * 45 on the 2026-09-10 walk's connection 1), and nothing arrives on any path.
 *
 * Tier A sees the driver, not quiche's connection-id pool (RFC_DETERMINISTIC_SIMULATION.md §4), so
 * what these pin is the driver-visible half of the walks: a dead socket's send faults cost the
 * connection nothing, the link change is observed, and the connection ends on its own idle deadline
 * with a typed reason — exactly 30s after the last datagram in, as both phones recorded it. The
 * pool half — why the three probes the reactor did make were the last it could — is
 * `MigrationSimTestSuite`'s two walk scenarios, against real quiche.
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
    fun walk_deterministic3x() =
        runTest {
            for (fixture in listOf(walk20260912Conn7DeadLinkHandoff)) {
                val golden = runWalk(fixture).trace.events
                repeat(3) { assertEquals(golden, runWalk(fixture).trace.events, "${fixture.name} is not deterministic") }
            }
        }
}
