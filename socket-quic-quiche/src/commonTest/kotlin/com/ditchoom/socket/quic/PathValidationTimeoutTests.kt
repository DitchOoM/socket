@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.sim.SimClock
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **A migration that quiche never answers must still end.**
 *
 * Found on real hardware, not in a lab: on a Wi-Fi↔cellular handoff walk (2026-08-17) an Android probe
 * reached [QuicPathState.Probing] at t=482s and quiche then reported neither `Validated` nor
 * `FailedValidation` — ever. Two `PATH_STATE` lines in a 2194-line trace. A second, genuine network
 * transition at t=646s produced **zero** migration attempts. iOS did the same.
 *
 * The cascade is all one root cause — path validation was the driver's only unbounded operation:
 *
 *  1. `pendingMigration` stays armed for the connection's life;
 *  2. so the caller's `CompletableDeferred` never completes and `migrate()` never returns (all three
 *     client implementations `await()` it with no timeout);
 *  3. so the automatic-migration reactor — a single sequential `collect` that awaits each `migrate()` —
 *     is parked forever, and every later handoff goes unmigrated.
 *
 * The connection survived that walk only because the old path happened to still work. On a handoff where
 * the old path is genuinely dead, this is the whole failure.
 *
 * RFC 9000 §8.2.4 already prescribes the answer — *"Endpoints SHOULD abandon path validation based on a
 * timer … three times the larger of the current PTO or the PTO for the new path (using kInitialRtt)"* —
 * and these tests pin it end to end: the deadline exists, it is not too eager, it clears the single-slot
 * guard, it frees the probed path, it releases the reactor, and it loses to a path that actually
 * validated.
 *
 * Everything runs on the [SimClock] virtual clock. That is not a convenience: [RealDriverClock]'s
 * `markNow()` measures *wall* time, which barely moves under `advanceTimeBy`, so a deadline measured off
 * it would never expire here and every test below would pass by never running its assertion.
 */
class PathValidationTimeoutTests {
    private val bufferFactory = BufferFactory.deterministic()

    /**
     * Fake pinned sockaddrs. Never dereferenced — [StubQuicheApi] decodes them from its registry rather
     * than from memory — and deliberately in the first pages, which no real allocation can occupy, so
     * they can never collide with the driver's own `peLocalOut` buffer address.
     */
    private val peerAddr = 0x1100L
    private val primaryAddr = 0x1200L

    /** The primary path's port, and the base for each probed path's (`PROBE_PORT_BASE + n`). */
    private val primaryPort = 40000
    private val probePortBase = 41000

    /**
     * `3 × max(currentPto, initialPto)`. [StubQuicheApi] binds no path stats (`connPathStats` → null,
     * the [QuicheApi] interface default), which is the RFC's own "using kInitialRtt" branch, so the
     * budget is `3 × (333ms + 4 × 166.5ms)` = 2997 ms. Spelled out here rather than imported so a
     * silent change to the formula fails these tests instead of moving with them.
     */
    private val expectedBudget = 2997.milliseconds

    /** Counts `recvInfoFree` so an abandoned path's leak can be measured, not assumed. */
    private class CountingFreeApi(
        delegate: QuicheApi,
    ) : QuicheApi by delegate {
        var recvInfoFrees = 0
            private set

        override fun recvInfoFree(info: QuicheRecvInfo) {
            recvInfoFrees++
        }
    }

    /**
     * A [UdpChannelFactory] that actually hands back a usable path — the piece `NeverCalledChannelFactory`
     * deliberately is not.
     *
     * Each `openPath` mints a **distinct** synthetic sockaddr and registers it with [api], so the probed
     * path decodes to a [PathKey] of its own. Without that every path collides on `PathKey(0,0,0,0)` and
     * the probed entry silently replaces the primary in the driver's routing map, which would make the
     * teardown assertions measure the wrong entry.
     */
    private class ScriptedPathFactory(
        private val api: StubQuicheApi,
        private val portBase: Int,
        override val localEndpointSupport: LocalEndpointSupport = LocalEndpointSupport.Bindable,
    ) : UdpChannelFactory {
        val channels = mutableListOf<StubUdpChannel>()
        val opened: Int get() = channels.size

        var releases = 0
            private set

        /** The local port [openPath] will assign to its next path — what a path event must name. */
        fun portOfPath(index: Int): Int = portBase + index

        /** The synthetic sockaddr [openPath] minted for path [index] — what a send-info `from` can name. */
        fun sockAddrOfPath(index: Int): Long = SOCKADDR_BASE + index * SOCKADDR_STRIDE

        override suspend fun openPath(
            localHost: String?,
            localPort: Int,
        ): NewPath {
            val index = channels.size + 1
            val sockAddr = SOCKADDR_BASE + index * SOCKADDR_STRIDE
            val port = portOfPath(index)
            api.registerSockAddr(sockAddr, port)
            val channel = StubUdpChannel()
            channels += channel
            return NewPath(
                channel = channel,
                localSockAddrAddress = sockAddr,
                localSockAddrLength = 16,
                localEndpoint = QuicLocalEndpoint("127.0.0.1", port),
                release = { releases++ },
            )
        }

        private companion object {
            const val SOCKADDR_BASE = 0x2000L
            const val SOCKADDR_STRIDE = 0x100L
        }
    }

    /** Everything one of these tests needs to drive and observe a migration. */
    private inner class Fixture(
        scope: TestScope,
    ) {
        val stub =
            StubQuicheApi().apply {
                established = true
                // The peer has issued a spare destination connection id, so RFC 9000 §9.5 is satisfied
                // and handleMigrate reaches the probe instead of answering NoSpareConnectionId.
                availableDcids = 1L
                registerSockAddr(primaryAddr, primaryPort)
            }
        val api = CountingFreeApi(stub)
        val factory = ScriptedPathFactory(stub, probePortBase)
        val primaryChannel = StubUdpChannel()

        val driver =
            QuicheDriver(
                migration =
                    MigrationCapability.Supported(
                        peer = PinnedSockAddr(peerAddr, 16),
                        primaryLocal = PinnedSockAddr(primaryAddr, 16),
                        channelFactory = factory,
                    ),
                rawApi = api,
                conn = QuicheConn(1L),
                bufferFactory = bufferFactory,
                recvInfo = QuicheRecvInfo(1L),
                sendInfo = QuicheSendInfo(1L),
                udpChannel = primaryChannel,
                // No primary reader loop: this stub channel parks in receive() forever and the tests
                // never deliver a datagram. The probed path still starts its own reader, which is what
                // the teardown assertions are about.
                clientMode = false,
                isServer = false,
                clock = SimClock(scope.testScheduler),
                driverContext = EmptyCoroutineContext,
            )

        /** Ask the driver to migrate, exactly as every platform `QuicConnection.migrate` does. */
        suspend fun migrate(): CompletableDeferred<MigrationResult> {
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(MigrationTarget.FreshLocalEndpoint, deferred))
            return deferred
        }

        /** One benign driver-loop wake (a no-op stream open), so `afterCommand`'s flush runs. */
        suspend fun wake() {
            driver.commands.send(QuicheCmd.OpenStream(CompletableDeferred()))
        }
    }

    /**
     * **The regression.** No path event ever arrives; the RFC 9000 §8.2.4 timer has to end the migration
     * on its own — and, critically, leave the connection able to migrate again.
     *
     * The last assertion is the one that proves the defect is gone rather than merely reported: a second
     * `migrate()` that came back [MigrationResult.Unmoved.Failed.AlreadyInProgress] would mean
     * `pendingMigration` was never cleared and the connection is wedged for good, which is exactly the
     * state the hardware walk was in.
     */
    @Test
    fun anUnansweredProbeIsAbandonedOnTheRfcTimerAndLeavesTheConnectionAbleToMigrateAgain() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            try {
                runCurrent()
                val first = f.migrate()
                runCurrent()

                // Anti-vacuity: without a real probe in flight the rest of this test proves nothing.
                assertEquals(1, f.factory.opened, "handleMigrate never opened a path — no probe was armed")
                assertIs<QuicPathState.Probing>(f.driver.pathState.value)
                assertFalse(first.isCompleted, "migrate() answered before any path event or deadline")

                // Just short of the deadline. This half is what stops a too-eager timer from passing:
                // an abandon budget of ~0 would already have failed the probe here.
                testScheduler.advanceTimeBy(expectedBudget - 1.milliseconds)
                runCurrent()
                assertFalse(
                    first.isCompleted,
                    "path validation was abandoned before the RFC 9000 §8.2.4 budget ($expectedBudget) elapsed",
                )

                testScheduler.advanceTimeBy(2.milliseconds)
                runCurrent()

                assertTrue(
                    first.isCompleted,
                    "the probe was never answered and the deadline passed, yet migrate() is still " +
                        "suspended — this is the defect: an unbounded path validation parks the caller " +
                        "(and the automatic reactor) for the life of the connection",
                )
                assertEquals(MigrationResult.Unmoved.Failed.PathNotValidated, first.await())
                assertEquals(
                    QuicPathState.Failed(MigrationResult.Unmoved.Failed.PathNotValidated),
                    f.driver.pathState.value,
                    "pathState must carry the same verdict migrate() reported",
                )

                // The wedge check. A cleared slot accepts a new probe; a stuck one answers AlreadyInProgress.
                val second = f.migrate()
                runCurrent()
                assertEquals(
                    2,
                    f.factory.opened,
                    "the second migration never reached the probe — pendingMigration was not cleared, so " +
                        "every later attempt answers AlreadyInProgress and the connection can never move again",
                )
                assertFalse(second.isCompleted, "the second migration should be probing, not already answered")
                assertIs<QuicPathState.Probing>(f.driver.pathState.value)
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **The leak.** Abandoning a probe must release everything opening it acquired — mirroring the
     * `FailedValidation` arm — and must do so *then*, while the connection is still live, not at
     * `cleanup()` when it dies. A flapping network takes this path over and over.
     */
    @Test
    fun anAbandonedProbeReleasesItsSocketRecvInfoAndSockaddr() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            try {
                runCurrent()
                f.migrate()
                runCurrent()
                val probed = f.factory.channels.single()
                assertEquals(0, probed.closeCount)
                assertEquals(0, f.api.recvInfoFrees)

                testScheduler.advanceTimeBy(expectedBudget + 1.milliseconds)
                runCurrent()

                // Measured before destroy(): cleanup() would free all three anyway, so asserting after
                // teardown would pass with no expiry-time teardown at all.
                assertEquals(1, probed.closeCount, "the abandoned path's UDP socket was left open")
                assertEquals(1, f.api.recvInfoFrees, "the abandoned path's recv_info was never freed")
                assertEquals(1, f.factory.releases, "the abandoned path's pinned sockaddr was never released")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **The ordering trap.** `afterCommand()` — which drains quiche's path events — runs *after* the
     * driver's timer branch, so a `Validated` event can be sitting in quiche's queue on the very wake the
     * deadline comes due. Expiring first would fail a path the peer actually answered.
     *
     * The event is enqueued while the loop is parked, so nothing can consume it until that wake: this is
     * a genuine same-wake race, and it goes red (`PathNotValidated`) the moment the drain is moved after
     * the expiry check.
     */
    @Test
    fun aPathValidatedInTheSameWakeAsTheDeadlineStillWins() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                assertEquals(1, f.factory.opened)

                // Queued while the driver is parked in select(), so the ONLY chance to read it is the
                // same wake that the abandon timer fires on.
                f.stub.pathEvents += StubPathEvent(QuichePathEventType.Validated, f.factory.portOfPath(1))
                assertFalse(result.isCompleted, "the event must still be unread when the deadline fires")

                testScheduler.advanceTimeBy(expectedBudget + 1.milliseconds)
                runCurrent()

                assertEquals(
                    MigrationResult.Succeeded(QuicLocalEndpoint("127.0.0.1", f.factory.portOfPath(1))),
                    result.await(),
                    "a path quiche validated was reported as a timeout — the abandon check ran before " +
                        "the path-event drain, so the deadline beat an event that was already queued",
                )
                assertEquals(
                    QuicPathState.Migrated(QuicLocalEndpoint("127.0.0.1", f.factory.portOfPath(1))),
                    f.driver.pathState.value,
                )
                assertEquals(0, f.api.recvInfoFrees, "a validated path must not be torn down")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **The reactor is released** — the behaviour the hardware walk found missing, at the layer it went
     * missing at.
     *
     * [wireAutoMigration] is a single sequential `collect` that awaits each `migrate()` to completion (a
     * deliberate design: see its KDoc, and `AutoMigrationReactorTests.changesDuringAnInFlightMigrationCoalesce`,
     * which forbids a quiet period). That await is only safe if `migrate()` is bounded. With a hung probe
     * it is not, and a second network transition — 23 of them on the real walk — produces nothing at all.
     *
     * Driven through a [QuicConnection] whose `migrate` is the three platforms' byte-identical body, so
     * the bound really is inherited from the driver rather than added here.
     */
    @Test
    fun aSecondNetworkChangeAfterAHungProbeStillMigrates() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            val wifi = NetworkId.Link(NetworkKind.Wifi, 1L)
            val cellular = NetworkId.Link(NetworkKind.Cellular, 2L)
            val ethernet = NetworkId.Link(NetworkKind.Ethernet, 3L)
            val monitor = SimNetworkMonitor.on(wifi)
            val connection = DriverBackedConnection(f.driver, UnconfinedTestDispatcher(testScheduler))
            try {
                runCurrent()
                wireAutoMigration(
                    QuicOptions(
                        alpnProtocols = listOf("test"),
                        migration = MigrationPolicy.Automatic,
                        networkMonitor = NetworkMonitorSource.Supplied(monitor),
                    ),
                    connection,
                    monitor,
                )
                runCurrent()

                monitor.setNetworkId(cellular) // the handoff: migration #1 arms a probe and parks
                runCurrent()
                assertEquals(1, connection.migrateCalls)
                assertEquals(1, f.factory.opened)

                // A second, genuine transition while the probe hangs. The reactor is parked inside
                // migrate(), so the StateFlow conflates and nothing happens yet — that part is correct
                // and is what the coalescing test protects.
                monitor.setNetworkId(ethernet)
                runCurrent()
                assertEquals(1, f.factory.opened, "no second migration may start while one is in flight")

                testScheduler.advanceTimeBy(expectedBudget + 1.milliseconds)
                runCurrent()

                assertEquals(
                    2,
                    connection.migrateCalls,
                    "the reactor never resumed: with an unbounded probe, migrate() never returns, so " +
                        "every later network change is silently unmigrated — 23 transitions produced one " +
                        "attempt on the real handoff walk",
                )
                assertEquals(2, f.factory.opened, "the second attempt must reach the probe, not be refused")
            } finally {
                connection.stop()
                f.driver.destroy()
            }
        }

    /**
     * A [QuicConnection] over a real [QuicheDriver], carrying the same `migrate` body all three platform
     * connections have (send [QuicheCmd.Migrate], `await()` the deferred with no timeout of its own).
     * Copied rather than shared because the three real ones live in platform source sets this common test
     * cannot see — and because the point of the test is that the bound comes from the *driver*.
     */
    private class DriverBackedConnection(
        private val driver: QuicheDriver,
        dispatcher: CoroutineContext,
    ) : QuicConnection {
        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext = dispatcher + job

        var migrateCalls = 0
            private set

        override suspend fun migrate(target: MigrationTarget): MigrationResult {
            migrateCalls++
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(target, deferred))
            return deferred.await()
        }

        fun stop() = job.cancel()

        override val identity: QuicConnectionIdentity =
            QuicConnectionIdentity(
                session = QuicSessionId("driver-backed-session"),
                wire = QuicWireConnectionId.Known("driver-backed-wire"),
            )

        // --- Unused by the reactor; present only to satisfy the interface. ---
        override val bufferFactory: BufferFactory get() = BufferFactory.Default
        override val state: StateFlow<QuicConnectionState> get() = driver.state

        override suspend fun openStream(): QuicByteStream = error("unused")

        override suspend fun acceptStream(): QuicByteStream = error("unused")

        override fun streams(): Flow<QuicByteStream> = throw UnsupportedOperationException("unused")

        override suspend fun close(error: QuicError) = Unit
    }

    /**
     * The budget is *derived*, never configured — RFC 9000 §8.2.4's formula is self-tuning, and a
     * [QuicOptions] knob would add a number that can contradict the migration policy. This pins the one
     * value the derivation produces when no RTT sample exists (`kInitialRtt`, the RFC's own branch),
     * measured through the driver rather than by re-deriving it.
     */
    @Test
    fun theAbandonBudgetIsThreeInitialPtosWhenNoPathStatsExist() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                val armedAt = testScheduler.currentTime

                // Walk to the deadline in one step short, then one step over: the transition pins the
                // exact value, not merely "some timeout eventually happened".
                testScheduler.advanceTimeBy(expectedBudget - 1.milliseconds)
                runCurrent()
                assertFalse(result.isCompleted)
                testScheduler.advanceTimeBy(2.milliseconds)
                runCurrent()
                assertTrue(result.isCompleted)

                assertEquals(
                    expectedBudget.inWholeMilliseconds,
                    testScheduler.currentTime - armedAt - 1,
                    "3 × max(currentPto, kInitialRtt PTO) with no path stats is 3 × 999ms",
                )
                // And the floor is real: ~3s, not something that could fire inside a normal RTT.
                assertTrue(expectedBudget >= 3.seconds - 3.milliseconds)
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **An abandoned probe's re-armed PATH_CHALLENGE is dropped, not misrouted out the primary
     * socket** (issue #395 item 4).
     *
     * Abandoning tears the driver's path entry down, but quiche still holds that path in
     * `Validating` and re-arms `challenge_requested` on each PTO for up to `MAX_PROBING_TIMEOUTS`
     * (path.rs). During that window `get_send_path_id` schedules datagrams whose egress address is
     * the abandoned path — a path this driver has no socket for. Routing them "somewhere" sends a
     * PATH_CHALLENGE out the wrong 4-tuple (the peer answers an address that never asked), and if
     * that fallback socket's send fails, the [SendOutcome.Failed] arm aborts the entire flush —
     * stream data queued behind the challenge included — for up to three PTOs. A datagram for a
     * torn-down path has exactly one correct treatment: it is already lost, and RFC 9002 loss
     * recovery owns it. Newly reachable since the 9f462e69 abandon timer, so it is pinned here
     * beside the timer's own tests.
     */
    @Test
    fun anAbandonedProbesReArmedChallengeIsDroppedNotMisroutedOutThePrimarySocket() =
        runTest {
            val f = Fixture(this)
            f.driver.start(this)
            try {
                runCurrent()
                val result = f.migrate()
                runCurrent()
                assertEquals(1, f.factory.opened, "no probe was armed — nothing to abandon")

                testScheduler.advanceTimeBy(expectedBudget + 1.milliseconds)
                runCurrent()
                assertEquals(MigrationResult.Unmoved.Failed.PathNotValidated, result.await())
                assertEquals(
                    1,
                    f.factory.channels
                        .single()
                        .closeCount,
                    "the abandon must have torn the probed path down for this test to mean anything",
                )
                val sendsBefore = f.primaryChannel.sendCount

                // The next flush carries two datagrams: quiche schedules the first on the abandoned
                // path (its re-armed challenge), the second on the primary.
                f.stub.connSendQueue += 1200
                f.stub.connSendQueue += 1200
                f.stub.sendInfoFromAddrQueue += f.factory.sockAddrOfPath(1)
                f.stub.sendInfoFromAddrQueue += primaryAddr
                f.wake()
                runCurrent()

                assertEquals(
                    0,
                    f.factory.channels
                        .single()
                        .sendCount,
                    "a datagram reached the abandoned path's closed socket",
                )
                assertEquals(
                    sendsBefore + 1,
                    f.primaryChannel.sendCount,
                    "exactly the primary-addressed datagram must egress the primary socket: one more " +
                        "means the abandoned path's PATH_CHALLENGE was misrouted out the primary 4-tuple, " +
                        "one fewer means the flush stalled behind the undeliverable datagram (#395 item 4)",
                )
            } finally {
                f.driver.destroy()
            }
        }
}
