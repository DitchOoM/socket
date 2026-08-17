package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkObservation
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ObservationSequence
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.SelectBuilder
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/**
 * [QuicConnection.networkAtClose] — the correlation Phase 3b specified and deferred.
 *
 * The load-bearing property is the **freeze**: once a connection is closed, this must answer "what was
 * the network doing when this died?", not "what is it doing now?". A test that reads the value
 * immediately after the close cannot tell those apart, so every assertion here moves time and changes
 * the network *after* the close and asserts the reported value did not follow.
 *
 * Time is driven by a [SteppableClock] rather than wall-clock, so `sinceLastChange` is an exact
 * expected value instead of a tolerance.
 */
class NetworkAtCloseTests {
    private val bufferFactory = BufferFactory.deterministic()
    private val wifi = NetworkId.Link(NetworkKind.Wifi, 1L)

    /**
     * A [DriverClock] whose time the test sets by hand and whose timer never fires.
     *
     * [ManualDriverClock] cannot be used here: its `advance` is a rendezvous with the driver's armed
     * `select`, so it only works while the loop is running — and this suite has to move time *after* the
     * connection has closed, which is exactly when there is no loop left to rendezvous with.
     */
    private class SteppableClock : DriverClock {
        var now: Duration = Duration.ZERO

        override fun markNow(): TimeMark {
            val origin = now
            return object : TimeMark {
                override fun elapsedNow(): Duration = now - origin
            }
        }

        override fun armTimeout(
            builder: SelectBuilder<QuicheCmd?>,
            wait: Duration,
        ) {
            // Deliberately registers nothing: this suite never wants a timer wake, and the driver's
            // select still races the command channel, which is how the close arrives.
        }
    }

    /**
     * A monitor that reports **density**: every platform observation reaches [observations], including
     * consecutive ones that fold to the same [NetworkState].
     *
     * [SimNetworkMonitor] cannot express this — its `observations` derive from a `StateFlow`, which
     * de-dupes equal values, so a repeated state simply never emits. That de-duplication is precisely
     * what [NetworkObservation] exists to see past, so testing the fold needs a monitor that speaks it.
     */
    private class DenseMonitor(
        initial: NetworkState,
    ) : NetworkMonitor {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<NetworkState> = _state

        private val _observations = MutableSharedFlow<NetworkObservation>(extraBufferCapacity = 64)
        override val observations: Flow<NetworkObservation> = _observations

        private var sequence = 0L

        /** One platform observation — published to both [state] and [observations]. */
        suspend fun observe(newState: NetworkState) {
            _state.value = newState
            sequence++
            _observations.emit(NetworkObservation.Sequenced(newState, ObservationSequence(sequence)))
        }

        override fun close() {}
    }

    private fun driverWith(
        observation: ConnectionNetworkObservation,
        clock: DriverClock,
    ): QuicheDriver =
        QuicheDriver(
            // Test double: never exercises a path move.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = StubQuicheApi(),
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = false,
            clock = clock,
            driverContext = EmptyCoroutineContext,
            networkObservation = observation,
        )

    @Test
    fun aConnectionNobodyObservesReportsNotObserved() =
        runTest {
            // No monitor resolved at all — a server-accepted connection, or any test double. Said with
            // a value, not a null: NetworkAtClose already has the case, so a nullable would be a second
            // encoding of it.
            val driver = driverWith(ConnectionNetworkObservation.Unobserved, clock = SteppableClock())
            driver.start(this)
            runCurrent()
            assertEquals(NetworkAtClose.NotObserved, driver.networkAtClose)
            driver.destroy()
            assertEquals(NetworkAtClose.NotObserved, driver.networkAtClose)
        }

    @Test
    fun alwaysAvailableMonitorReportsNotObserved() =
        runTest {
            // AlwaysAvailable never transitions; NetworkAtClose.NotObserved's own KDoc names it as the
            // honest case, so reporting a fabricated Observed here would be the lie this type exists to
            // prevent.
            val clock = SteppableClock()
            val observation = ConnectionNetworkObservation.of(NetworkMonitor.AlwaysAvailable, clock)
            val driver = driverWith(observation, clock)
            driver.start(this)
            observation.collectInto(backgroundScope)
            runCurrent()
            assertEquals(NetworkAtClose.NotObserved, driver.networkAtClose)
            driver.destroy()
            assertEquals(NetworkAtClose.NotObserved, driver.networkAtClose)
        }

    @Test
    fun observationIsFrozenAtTheCloseTransition() =
        runTest {
            val clock = SteppableClock()
            val monitor = SimNetworkMonitor.on(wifi)
            val observation = ConnectionNetworkObservation.of(monitor, clock)
            val driver = driverWith(observation, clock)
            driver.start(this)
            observation.collectInto(backgroundScope)
            runCurrent()

            // The link goes down 4s before the connection dies, and the connection then lives 3s on the
            // dead link. That 3s is the whole diagnostic value: it says the close followed the change.
            clock.now += 4.seconds
            monitor.set(NetworkState.Offline)
            runCurrent()
            clock.now += 3.seconds

            driver.destroy()
            val atClose = driver.networkAtClose
            val observed = assertIs<NetworkAtClose.Observed>(atClose)
            assertEquals(NetworkState.Offline, observed.observation.state)
            assertEquals(3.seconds, observed.sinceLastChange)

            // …and now the world moves on: an hour passes and the network comes back on a new link.
            // A value that followed would be answering "what is the network doing now?" — which is the
            // question this type exists NOT to answer.
            clock.now += 1.seconds * 3600
            monitor.set(NetworkState.Routable(NetworkId.Link(NetworkKind.Cellular, 2L), InternetAccess.Observed.Confirmed))
            runCurrent()
            assertEquals(atClose, driver.networkAtClose, "networkAtClose must be frozen at the close transition")
        }

    /**
     * The density case. Observations that fold to the **same** state are the signal a link is flapping
     * hard while every evaluation lands on the same rung; treating them as changes would reset
     * `sinceLastChange` and erase exactly that information — the difference between "killed by something
     * periodic" and "killed by the transition".
     */
    @Test
    fun sinceLastChangeDoesNotResetOnAFoldToTheSameState() =
        runTest {
            val clock = SteppableClock()
            val monitor = DenseMonitor(NetworkState.Routable(wifi, InternetAccess.Unobserved))
            val observation = ConnectionNetworkObservation.of(monitor, clock)
            val driver = driverWith(observation, clock)
            driver.start(this)
            observation.collectInto(backgroundScope)
            runCurrent()

            clock.now += 2.seconds
            monitor.observe(NetworkState.Offline) // a real change: the mark restarts here
            runCurrent()

            clock.now += 5.seconds
            monitor.observe(NetworkState.Offline) // folds to the same state: chatter, not a change
            runCurrent()

            clock.now += 1.seconds
            driver.destroy()
            val observed = assertIs<NetworkAtClose.Observed>(driver.networkAtClose)
            assertEquals(
                ObservationSequence(2),
                assertIs<NetworkObservation.Sequenced>(observed.observation).sequence,
                "the second (folded) observation must still be the one reported",
            )
            assertEquals(
                6.seconds,
                observed.sinceLastChange,
                "a fold back to the same state is density, not a change, and must not reset the mark",
            )
        }

    /**
     * A connection that dies before its observation collector has produced anything still reports the
     * state it connected on — read straight from the monitor, and honestly [NetworkObservation.Unsequenced]
     * because a state read carries no position in the observation stream.
     */
    @Test
    fun aCloseBeforeTheFirstEmissionStillReportsTheConnectTimeState() =
        runTest {
            val clock = SteppableClock()
            val monitor = SimNetworkMonitor.on(wifi)
            val observation = ConnectionNetworkObservation.of(monitor, clock)
            val observed = assertIs<NetworkAtClose.Observed>(observation.atClose)
            assertEquals(NetworkState.Routable(wifi, InternetAccess.Unobserved), observed.observation.state)
            assertIs<NetworkObservation.Unsequenced>(observed.observation)
        }

    /** [QuicCloseContext] is the post-mortem: which connection, why it ended, and what the network was doing. */
    @Test
    fun closeContextRendersIdentityReasonAndNetwork() =
        runTest {
            val clock = SteppableClock()
            val monitor = SimNetworkMonitor.on(wifi)
            val observation = ConnectionNetworkObservation.of(monitor, clock)
            val context =
                QuicCloseContext(
                    identity =
                        QuicConnectionIdentity(
                            session = QuicSessionId("s-1"),
                            wire = QuicWireConnectionId.Known("cafe"),
                        ),
                    reason = QuicCloseReason.ByLocal(QuicError.IdleTimeout),
                    network = observation.atClose,
                )
            val rendered = context.toString()
            assertTrue(rendered.contains("session=s-1"), rendered)
            assertTrue(rendered.contains("wire="), rendered)
            assertTrue(rendered.contains("reason="), rendered)
            assertTrue(rendered.contains("network="), rendered)
        }
}
