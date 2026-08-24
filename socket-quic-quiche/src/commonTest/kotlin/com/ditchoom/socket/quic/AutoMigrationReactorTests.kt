@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.BlockReason
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Unit coverage for the [wireAutoMigration] reactor branches, isolated from any real backend.
 *
 * The end-to-end [QuicAutoMigrationTests] proves the happy path over a live quiche connection; here we
 * drive the reactor with a [RecordingQuicConnection] (which records [QuicConnection.migrate] calls and
 * can be made to *suspend* inside one, exactly as the real connection does) and a scriptable
 * [SimNetworkMonitor], so every guard — the two non-automatic policies, the `AlwaysAvailable`
 * short-circuit, the `canRouteOffLink` filter, the `Unidentified` filter, the connect-time baseline,
 * repeated handoffs, the `Impossible`/`Failed` split, and the in-flight coalescing that makes a quiet
 * period unnecessary — is asserted deterministically with no network at all.
 *
 * Runs on every platform (no native lib), under [runTest] with an [UnconfinedTestDispatcher] so a
 * `networkId` change synchronously drives the collector and `migrate` bookkeeping before we assert.
 */
class AutoMigrationReactorTests {
    /**
     * How long the real connection survived after its probe was lost before `IdleTimeout` killed it,
     * on the 2026-08-23 walk. Any retry has to happen inside this window to be worth anything, so it
     * is the honest deadline for the assertion rather than a number chosen to make a test pass.
     */
    private val idleTimeoutInTheField = 30.seconds

    /**
     * The window the two cadence tests compare. Long enough that the backoff has reached its ceiling
     * inside the *second* one, so "fewer attempts later" is the schedule and not sampling noise.
     */
    private val observationWindow = 60.seconds

    /** How many further windows [retryingIsNotBoundedByACount] runs for. Virtual time, so it is free. */
    private val longRunWindows = 30

    private val wifi = NetworkId.Link(NetworkKind.Wifi, 1L)
    private val cellular = NetworkId.Link(NetworkKind.Cellular, 2L)
    private val ethernet = NetworkId.Link(NetworkKind.Ethernet, 3L)

    /**
     * A [QuicConnection] that records `migrate` calls and answers a scripted [MigrationResult].
     *
     * [gate] models the real connection's defining property: `migrate()` suspends until the driver
     * completes the path move ([JvmQuicConnection] awaits a `CompletableDeferred` the path-event drain
     * completes). When a test installs a gate, `migrate` parks on it — which is the only way to observe
     * what the reactor does while a migration is *in flight*.
     */
    private class RecordingQuicConnection(
        dispatcher: CoroutineContext,
        private val result: MigrationResult,
    ) : QuicConnection {
        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext = dispatcher + job

        /** No quiche connection behind this double; the reactor under test never reads identity. */
        override val identity: QuicConnectionIdentity =
            QuicConnectionIdentity(
                session = QuicSessionId("recording-session"),
                wire = QuicWireConnectionId.Known("recording-wire"),
            )

        val migrateArgs = mutableListOf<MigrationTarget>()
        val migrateCount: Int get() = migrateArgs.size

        /** Set by a test to make the next `migrate` calls park; complete it to let them return. */
        var gate: CompletableDeferred<Unit>? = null

        /** How many `migrate` calls have returned. `migrateCount - completed` is the in-flight count. */
        var completedCount: Int = 0
            private set

        override suspend fun migrate(target: MigrationTarget): MigrationResult {
            migrateArgs += target
            gate?.await()
            completedCount++
            return result
        }

        fun stop() = job.cancel()

        // --- Unused by the reactor; present only to satisfy the interface. ---
        override val bufferFactory: BufferFactory get() = BufferFactory.Default
        override val state: StateFlow<QuicConnectionState> = MutableStateFlow(QuicConnectionState.Idle)

        override suspend fun openStream(): QuicByteStream = error("unused")

        override suspend fun acceptStream(): QuicByteStream = error("unused")

        override fun streams(): Flow<QuicByteStream> = throw UnsupportedOperationException("unused")

        override suspend fun close(error: QuicError) = Unit
    }

    private fun options(
        monitor: NetworkMonitor,
        policy: MigrationPolicy = MigrationPolicy.Automatic,
        // Both left at their QuicOptions defaults on purpose: the budget tests below are about what
        // the library actually ships, so they must not restate it.
        idleTimeout: Duration = QuicOptions(alpnProtocols = listOf("test")).idleTimeout,
        cidLimit: Long = QuicOptions(alpnProtocols = listOf("test")).activeConnectionIdLimit,
    ) = QuicOptions(
        alpnProtocols = listOf("test"),
        migration = policy,
        networkMonitor = NetworkMonitorSource.Supplied(monitor),
        idleTimeout = idleTimeout,
        activeConnectionIdLimit = cidLimit,
    )

    private fun runReactor(
        monitor: NetworkMonitor,
        policy: MigrationPolicy = MigrationPolicy.Automatic,
        migrateResult: MigrationResult = MigrationResult.Succeeded(QuicLocalEndpoint("127.0.0.1", 51234)),
        // Receiver, not just a parameter, so a test can advance the virtual clock. Every assertion in
        // this file used to be driven by a fresh `setNetworkId`, which is exactly the blind spot #453
        // lived in: the one thing no test could express was *time passing with no further input*.
        body: TestScope.(RecordingQuicConnection) -> Unit,
    ) = runTest {
        val connection = RecordingQuicConnection(UnconfinedTestDispatcher(testScheduler), migrateResult)
        try {
            wireAutoMigration(options(monitor, policy), connection, monitor)
            body(connection)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun forbiddenPolicyNeverObserves() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor, policy = MigrationPolicy.Forbidden) { conn ->
            monitor.setNetworkId(cellular)
            assertEquals(0, conn.migrateCount, "MigrationPolicy.Forbidden must not react to network changes")
        }
    }

    @Test
    fun manualPolicyNeverObserves() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor, policy = MigrationPolicy.Manual) { conn ->
            monitor.setNetworkId(cellular)
            assertEquals(0, conn.migrateCount, "MigrationPolicy.Manual must not react — only the app migrates")
        }
    }

    @Test
    fun alwaysAvailableMonitorLaunchesNoCollector() =
        runReactor(NetworkMonitor.AlwaysAvailable) { conn ->
            // AlwaysAvailable never changes its (Unidentified) identity; the reactor short-circuits
            // before even launching, so there is nothing to drive and no migration can occur.
            assertEquals(0, conn.migrateCount)
            assertTrue(
                conn.coroutineContext[Job]!!.children.none(),
                "AlwaysAvailable must not launch an observer",
            )
        }

    @Test
    fun firstIdentifiedLinkIsBaselineNotAMigration() {
        val monitor = SimNetworkMonitor.on(NetworkId.Unidentified)
        runReactor(monitor) { conn ->
            // The first *identified* link after connect is the baseline the connection already lives on.
            monitor.setNetworkId(wifi)
            assertEquals(0, conn.migrateCount, "the first identified link is the connect-time baseline")
            // Only a subsequent, distinct link is a real handoff.
            monitor.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount)
        }
    }

    @Test
    fun distinctLinkChangeMigratesWithEphemeralDefaults() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor) { conn ->
            monitor.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount)
            // The one target every platform serves — a fresh, platform-chosen local endpoint.
            assertEquals(MigrationTarget.FreshLocalEndpoint, conn.migrateArgs.single())
        }
    }

    @Test
    fun unidentifiedEmissionsAreIgnored() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor) { conn ->
            // A link momentarily vanishing (Unidentified) is not a migrate target and is not a baseline.
            monitor.setNetworkId(NetworkId.Unidentified)
            assertEquals(0, conn.migrateCount)
            monitor.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount, "the baseline must survive an Unidentified gap")
        }
    }

    @Test
    fun everyDistinctHandoffMigrates() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor) { conn ->
            monitor.setNetworkId(cellular)
            monitor.setNetworkId(ethernet)
            assertEquals(2, conn.migrateCount, "each distinct link change is its own migration")
        }
    }

    @Test
    fun unsupportedBackendStopsObservingAfterFirstAttempt() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor, migrateResult = MigrationResult.Unmoved.Impossible.BackendCannotMigrate) { conn ->
            monitor.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount)
            // Impossible → the observer cancels itself; further changes must not call migrate again.
            monitor.setNetworkId(ethernet)
            assertEquals(1, conn.migrateCount, "a backend that cannot migrate must stop being asked")
        }
    }

    /**
     * Every [MigrationResult.Unmoved.Impossible] leaf means "and never will, whatever the network does",
     * so each one must stop the observer — not just the one leaf the old `Unsupported` covered. Written
     * as a loop over the whole family so a new leaf that forgets this is a compile-visible omission at
     * the list, not a silent behaviour gap.
     */
    @Test
    fun impossibleResultStopsObserving() {
        val leaves: List<MigrationResult.Unmoved.Impossible> =
            listOf(
                MigrationResult.Unmoved.Impossible.ServerConnection,
                MigrationResult.Unmoved.Impossible.PolicyForbids,
                MigrationResult.Unmoved.Impossible.PeerForbids,
                MigrationResult.Unmoved.Impossible.BackendCannotMigrate,
                MigrationResult.Unmoved.Impossible.ConnectionClosed,
            )
        for (leaf in leaves) {
            val monitor = SimNetworkMonitor.on(wifi)
            runReactor(monitor, migrateResult = leaf) { conn ->
                monitor.setNetworkId(cellular)
                assertEquals(1, conn.migrateCount, "$leaf should have been attempted once")
                monitor.setNetworkId(ethernet)
                assertEquals(1, conn.migrateCount, "$leaf must stop the observer")
            }
        }
    }

    /**
     * The complement, and the case with no coverage before this phase: a
     * [MigrationResult.Unmoved.Failed] is "not this time", so the observer keeps watching and the next
     * distinct link is still followed. Treating it like `Impossible` would silently disable migration
     * for the rest of a connection's life after one transient failure.
     */
    @Test
    fun failedResultKeepsObserving() {
        val leaves: List<MigrationResult.Unmoved.Failed> =
            listOf(
                MigrationResult.Unmoved.Failed.EndpointNotSelectable,
                MigrationResult.Unmoved.Failed.AlreadyInProgress,
                MigrationResult.Unmoved.Failed.NoSpareConnectionId,
                MigrationResult.Unmoved.Failed.LocalPathUnavailable(IllegalStateException("no route")),
                MigrationResult.Unmoved.Failed.ProbeRejected(-7),
                MigrationResult.Unmoved.Failed.PathNotValidated,
                MigrationResult.Unmoved.Failed.SwitchRejected(-3),
            )
        for (leaf in leaves) {
            val monitor = SimNetworkMonitor.on(wifi)
            runReactor(monitor, migrateResult = leaf) { conn ->
                monitor.setNetworkId(cellular)
                monitor.setNetworkId(ethernet)
                assertEquals(2, conn.migrateCount, "$leaf must not stop the observer")
            }
        }
    }

    /**
     * A failed migration did not move the connection, so the link it failed to reach must **not** become
     * the recorded attachment — otherwise returning to the link we are actually still on would read as
     * "already attached" and a genuine recovery handoff would be skipped.
     */
    @Test
    fun failedMigrationDoesNotClaimTheNewLink() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor, migrateResult = MigrationResult.Unmoved.Failed.PathNotValidated) { conn ->
            monitor.setNetworkId(cellular) // attempt 1: fails; we are still on wifi
            assertEquals(1, conn.migrateCount)
            monitor.setNetworkId(wifi) // back to the link we never left…
            assertEquals(
                1,
                conn.migrateCount,
                "returning to the link we are still attached to is not a handoff",
            )
            monitor.setNetworkId(cellular) // …and cellular is still a genuine change
            assertEquals(2, conn.migrateCount)
        }
    }

    /**
     * **#453 — a failed probe must be retried while the active path is still dead.**
     *
     * Measured on a real Wi-Fi→cellular walk (2026-08-23): the reactor probed the cellular path once,
     * the PATH_CHALLENGE went unanswered (`PathNotValidated` after the RFC 9000 §8.2.4 abandon timer),
     * and nothing ever tried again. The connection sat on the dead Wi-Fi path through 57 consecutive
     * failed reads and died of `IdleTimeout` 30 seconds later.
     *
     * The reason is visible in [wireAutoMigration]: the collector is gated by `distinctUntilChanged()`
     * on `networkId`, so a retry needs the network *identity to change again* — but the handoff has
     * already happened and the phone is standing still on cellular, so that emission never comes. The
     * `Failed -> Unit` branch says "keep watching"; what it watches for is an event in the past.
     *
     * Note what this test does **not** do: it never calls `setNetworkId` a second time. Every other
     * failure case in this file drives recovery with a fresh link change, which is why they all pass
     * against the defect — they encode the same assumption the implementation does. The whole content
     * of this test is the thirty seconds in which *nothing happens*, because that is the field
     * condition, and an unanswered PATH_CHALLENGE is routine on real cellular rather than exotic.
     *
     * Deliberately asserts only that *a* retry occurs within the window the field gave us, not how
     * many or on what schedule: the cadence is the fix's to choose (and must stay bounded, or #453
     * becomes #385 by another route). The contract under test is simply that one lost probe does not
     * end migration for the life of the connection.
     */
    @Test
    fun aFailedProbeIsRetriedWhileTheActivePathIsStillDead() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor, migrateResult = MigrationResult.Unmoved.Failed.PathNotValidated) { conn ->
            monitor.setNetworkId(cellular) // the handoff: one probe, and it goes unanswered
            assertEquals(1, conn.migrateCount, "the handoff itself must attempt a migration")

            // …and now nothing else arrives. The device has finished handing off and is sitting
            // still, so there is no further link change to wake the collector — only time.
            advanceTimeBy(idleTimeoutInTheField)

            assertTrue(
                conn.migrateCount > 1,
                "a lost PATH_CHALLENGE must be retried while the active path is dead: after " +
                    "$idleTimeoutInTheField with no further network event the reactor made " +
                    "${conn.migrateCount} attempt(s), so the connection would idle out on a dead path (#453)",
            )
        }
    }

    /**
     * **The contract that makes a quiet period unnecessary.**
     *
     * `migrate()` suspends until the path move completes, so the collector is parked inside it while
     * further link changes arrive. The upstream `StateFlow` conflates, so those changes coalesce: when
     * the migration returns, the collector resumes on whichever link is current *then* — E — and never
     * spends a migration on C, which the device has already left.
     *
     * A timer-based quiet period would produce the same count here and be strictly worse elsewhere (it
     * would also refuse a handoff arriving after a *fast* migration). This is the property to protect.
     */
    @Test
    fun changesDuringAnInFlightMigrationCoalesce() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor) { conn ->
            val gate = CompletableDeferred<Unit>()
            conn.gate = gate

            monitor.setNetworkId(cellular) // starts migration #1 (to C); parks on the gate
            assertEquals(1, conn.migrateCount)
            assertEquals(0, conn.completedCount, "migrate must still be in flight")

            monitor.setNetworkId(wifi) // conflated away while we are parked
            monitor.setNetworkId(ethernet) // the link that is actually current when we resume
            assertEquals(1, conn.migrateCount, "no second migration may start while one is in flight")

            conn.gate = null
            gate.complete(Unit)
            assertEquals(2, conn.migrateCount, "exactly one further migration after the first completes")
            assertEquals(2, conn.completedCount, "both migrations must have run to completion")

            // Two, not three, is the whole proof. A reactor that did not suspend through the path move
            // would have taken W and E as separate emissions and spent a migration on each; because the
            // collector was parked inside migrate(), the StateFlow conflated them and it resumed on the
            // one link that was current then — E. W was never migrated to, and C was never re-migrated.
            monitor.setNetworkId(cellular)
            assertEquals(3, conn.migrateCount, "the observer is still live and follows the next handoff")
        }
    }

    /**
     * The `attachedTo` bookkeeping a naive `drop(1)` reactor gets wrong: while migrating to C the device
     * returns to W.
     *
     * If the migration **succeeded** we are now attached to C while the device is on W, so one more
     * migration (back to W) is exactly right. If it **failed** we never left W, so nothing further is
     * owed — and issuing a migration there would be a move to the link we are already on.
     */
    @Test
    fun aFlapThatComesHomeIssuesNoSecondMigration() {
        // Succeeded: we did move to C, so coming home to W is a real handoff.
        val succeededMonitor = SimNetworkMonitor.on(wifi)
        runReactor(succeededMonitor) { conn ->
            val gate = CompletableDeferred<Unit>()
            conn.gate = gate
            succeededMonitor.setNetworkId(cellular)
            succeededMonitor.setNetworkId(wifi)
            conn.gate = null
            gate.complete(Unit)
            assertEquals(2, conn.migrateCount, "after moving to C, returning to W is a genuine handoff")
        }

        // Failed: we never left W, so W is still where we are and there is nothing to do.
        val failedMonitor = SimNetworkMonitor.on(wifi)
        runReactor(failedMonitor, migrateResult = MigrationResult.Unmoved.Failed.PathNotValidated) { conn ->
            val gate = CompletableDeferred<Unit>()
            conn.gate = gate
            failedMonitor.setNetworkId(cellular)
            failedMonitor.setNetworkId(wifi)
            conn.gate = null
            gate.complete(Unit)
            assertEquals(1, conn.migrateCount, "a failed migration never left W, so coming home is a no-op")
        }
    }

    /**
     * Never migrate onto a link the monitor says traffic will not cross: probing it burns a spare
     * destination connection id and then fails validation.
     *
     * The recovery half is the real assertion. It only works because the `canRouteOffLink` filter sits
     * **before** the `map { it.networkId }`: the blocked link emits nothing, so when it later becomes
     * `Confirmed` its id is a *new* value to `distinctUntilChanged`. Filter after the map and the
     * recovery is swallowed, because the identity never changed.
     */
    @Test
    fun blockedLinkIsNotAMigrationTarget() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor) { conn ->
            monitor.set(NetworkState.Routable(cellular, InternetAccess.Observed.Blocked(BlockReason.CaptivePortal)))
            assertEquals(0, conn.migrateCount, "a captive-portal link is not worth a spare connection id")
            monitor.set(NetworkState.Routable(cellular, InternetAccess.Observed.Confirmed))
            assertEquals(1, conn.migrateCount, "the same link, once confirmed, IS a migration target")
        }
    }

    /**
     * [NetworkMonitorSource.Supplied] is honoured as-is: the reactor observes the instance the caller
     * handed [QuicOptions.networkMonitor] and never reaches for the process default. Asserted by
     * driving *only* the supplied monitor and seeing the migration happen.
     */
    @Test
    fun suppliedMonitorIsUsedWithoutProcessDefault() {
        val supplied = SimNetworkMonitor.on(wifi)
        assertEquals(supplied, (options(supplied).networkMonitor as NetworkMonitorSource.Supplied).monitor)
        assertEquals(supplied, resolveNetworkMonitor(NetworkMonitorSource.Supplied(supplied)))
        runReactor(supplied) { conn ->
            supplied.setNetworkId(cellular)
            assertEquals(1, conn.migrateCount)
        }
    }

    /**
     * **There is no attempt count, and there must not be one.**
     *
     * A count is a guess at when to stop, and there is nothing to guess: the loop already ends on four
     * facts it can *observe* — it succeeded, a different link arrived, the leaf says asking again cannot
     * help, or the connection is gone and answers
     * [MigrationResult.Unmoved.Impossible.ConnectionClosed], which cancels the observer. The case a
     * count was supposed to cover — a link that never answers while the old path keeps working — is
     * precisely the case where giving up is wrong: the platform's position is that we have left the
     * link we are sitting on, and a link unreachable now may not be in five minutes.
     *
     * The connection here never closes and every attempt fails, so nothing can stop the reactor. It must
     * still be asking at the end of a long window. Bounding the *cost* is the backoff's job, asserted
     * by [theRetryCadenceDecaysSoNeverGivingUpIsAffordable].
     */
    @Test
    fun retryingIsNotBoundedByACount() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor, migrateResult = MigrationResult.Unmoved.Failed.PathNotValidated) { conn ->
            monitor.setNetworkId(cellular)
            advanceTimeBy(observationWindow)
            val early = conn.migrateCount
            advanceTimeBy(observationWindow * longRunWindows)
            assertTrue(
                conn.migrateCount > early,
                "the reactor made $early attempt(s) in the first $observationWindow and then stopped: " +
                    "after a further ${observationWindow * longRunWindows} it is still at " +
                    "${conn.migrateCount}. Nothing observable told it to stop — the connection is open, " +
                    "the link has not changed, and the leaf is retryable — so this is a count, and a " +
                    "count is the thing that leaves a connection sitting on a link the platform says " +
                    "it has left",
            )
        }
    }

    /**
     * **What bounds the retry is its cadence, not a count.**
     *
     * Never giving up is only affordable if asking gets cheaper. The backoff doubles to a ceiling, so a
     * later window of the same length must contain strictly fewer attempts than an earlier one — and at
     * least one, or "does not give up" would be false in the only way that matters.
     *
     * Asserted as a comparison between two equal windows rather than against the schedule's constants,
     * so retuning the backoff cannot silently turn into retuning this test.
     */
    @Test
    fun theRetryCadenceDecaysSoNeverGivingUpIsAffordable() {
        val monitor = SimNetworkMonitor.on(wifi)
        runReactor(monitor, migrateResult = MigrationResult.Unmoved.Failed.PathNotValidated) { conn ->
            monitor.setNetworkId(cellular)
            advanceTimeBy(observationWindow)
            val firstWindow = conn.migrateCount
            advanceTimeBy(observationWindow)
            val secondWindow = conn.migrateCount - firstWindow

            assertTrue(
                secondWindow < firstWindow,
                "the reactor asked $firstWindow time(s) in the first $observationWindow and " +
                    "$secondWindow time(s) in the second — the cadence is not decaying, so a link that " +
                    "never answers costs a probe, a socket and a spare connection id at that rate for " +
                    "the life of the connection",
            )
            assertTrue(
                secondWindow >= 1,
                "the reactor asked $secondWindow time(s) in the second $observationWindow — it has " +
                    "effectively given up, so a link that becomes reachable later is never taken",
            )
        }
    }

    /**
     * [NetworkMonitorSource.ProcessDefault] resolves to the one process-shared monitor — the same
     * instance every time, which is what makes it "at most a single background socket/thread for the
     * whole process" rather than one per connection.
     */
    @Test
    fun processDefaultResolvesToTheOneSharedMonitor() {
        val first = resolveNetworkMonitor(NetworkMonitorSource.ProcessDefault)
        val second = resolveNetworkMonitor(NetworkMonitorSource.ProcessDefault)
        assertTrue(first === second, "the process default must be shared, not minted per connection")
    }
}
