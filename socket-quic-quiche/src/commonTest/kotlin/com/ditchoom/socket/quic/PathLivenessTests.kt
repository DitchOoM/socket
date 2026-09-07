@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **The #574 sampler itself, with quiche's counters written by hand.**
 *
 * `MigrationSimTests` drives this end to end against a real quiche pair, which is the right test for
 * "does the connection re-home" and the wrong one for everything below: each of these is about what the
 * sampler does with particular counter *values*, and the sim can only produce the values a real
 * connection happens to produce. At its 120ms round trip the two halves of [SilenceThreshold.isMetBy]
 * bind at the same expiry, so neither can be shown to be load-bearing there at all.
 *
 * The path-identity cases are not merely awkward there but unreachable. quiche can move the active path
 * with no `Migrated` event of ours: `on_failed_validation()` clears the active flag and the next
 * `on_timeout` picks a replacement through `find_candidate_path()` + `set_active_path()`, entirely
 * inside libquiche. `total_pto_count` is per path, so the read after that switch subtracts two
 * different paths' counters. Here it is three lines of setup.
 *
 * Runs on every platform: [StubQuicheApi], no native library, [SimClock] virtual time.
 */
class PathLivenessTests {
    private val bufferFactory = BufferFactory.deterministic()

    /**
     * The driver's default wake cadence here. Deliberately far shorter than
     * [SILENT_PATH_MINIMUM_SILENCE] so that the expiry count can be driven past its threshold *before*
     * the time floor is anywhere near met — which is how the count-bound half is observed. A test that
     * needs the other half passes a slower [Fixture] tick, so time runs out first instead.
     */
    private val tick = 100.milliseconds

    private inner class Fixture(
        scope: TestScope,
        val tick: Duration,
    ) {
        val stub =
            StubQuicheApi().apply {
                established = true
                connTimeout = this@Fixture.tick
                registerSockAddr(PRIMARY_ADDR, PRIMARY_PORT)
            }

        val driver =
            QuicheDriver(
                // Supported, because the sampler only runs for a client that could act on it.
                migration =
                    MigrationCapability.Supported(
                        peer = PinnedSockAddr(PEER_ADDR, 16),
                        primaryLocal = PinnedSockAddr(PRIMARY_ADDR, 16),
                        channelFactory = UnusedPathFactory,
                    ),
                rawApi = stub,
                conn = QuicheConn(1L),
                bufferFactory = bufferFactory,
                recvInfo = QuicheRecvInfo(1L),
                sendInfo = QuicheSendInfo(1L),
                udpChannel = StubUdpChannel(),
                clientMode = false,
                isServer = false,
                clock = SimClock(scope.testScheduler),
                driverContext = EmptyCoroutineContext,
            )

        /**
         * Publish one read of quiche's path table and let exactly one driver wake consume it.
         *
         * [activeIndex] is which path carries the active flag and [expiries] its cumulative
         * `total_pto_count`; every other path is inactive and reads zero, so a test that moves the
         * active flag is also moving to a counter that means nothing to the previous one — which is the
         * situation being tested.
         */
        fun read(
            scope: TestScope,
            expiries: Long,
            activeIndex: Int = 0,
            paths: Int = 1,
        ) {
            stub.pathStats =
                (0 until paths).map { index ->
                    pathStats(active = index == activeIndex, totalPtoCount = if (index == activeIndex) expiries else 0L)
                }
            scope.advanceTimeBy(tick)
            scope.runCurrent()
        }
    }

    /**
     * Start the driver with something collecting [QuicheDriver.pathLiveness], because the sampler is
     * gated on there being a collector — that gate is what keeps a `MigrationPolicy.Manual` connection
     * from paying for reads nobody will look at, and a test that forgot it would measure the gate
     * rather than the sampler.
     */
    private fun TestScope.startObserved(f: Fixture) {
        // backgroundScope, because neither the driver loop nor the collector ever completes on their
        // own and `runTest` waits for its children — the whole point of the fixture is a driver that
        // is still running when the assertions are made.
        f.driver.start(backgroundScope)
        backgroundScope.launch { f.driver.pathLiveness.collect { } }
        runCurrent()
    }

    /**
     * **The verdict lands at the floor, not at the next expiry** (#574's remaining quantisation).
     *
     * Both halves of the threshold can be satisfied long before anything wakes the driver to notice.
     * Expiries back off exponentially, so a verdict evaluated *only* at expiry wakes is pinned to
     * whichever expiry happens to fall past both conditions rather than to the conditions themselves.
     *
     * Two separate measurements, which describe different paths and must not be read as one:
     *  - on the sim's **120ms round trip** the expiries land at 245 / 735 / 1715 / 3675ms, so a 2s floor
     *    is met at ≈2.245s while the verdict waits for 3.675s;
     *  - end to end on a **20ms one-way** path, `aDeadPathReHomesWhileTheMonitorStillCallsTheLinkHealthy`
     *    measured **3.062s without this wake and 2.099s with it**.
     *
     * The floor did not move in either. The waiting did.
     *
     * Here the count is driven past the threshold at a fast tick, and quiche's own timer is then pushed
     * far out so that nothing but the driver's floor deadline can produce the verdict. If the wake is
     * removed, [PathLiveness.Silent] arrives at the 30s quiche wake instead of at the floor, and the
     * time assertion — not the state assertion — is what fails.
     */
    @Test
    fun theVerdictLandsAtTheFloorNotAtTheNextExpiry() =
        runTest {
            val f = Fixture(this, tick)
            startObserved(f)

            f.read(this, expiries = 0)
            var expiries = 0L
            repeat(SILENT_PATH_EXPIRY_THRESHOLD.toInt()) {
                expiries++
                f.read(this, expiries = expiries)
            }
            val countMetAt = currentTime.milliseconds
            assertEquals(
                PathLiveness.Answering,
                f.driver.pathLiveness.value,
                "the count was met at $countMetAt, well short of the $SILENT_PATH_MINIMUM_SILENCE floor, " +
                    "and the path was already called silent — the floor is not being applied",
            )

            // Quiche will not ask to be woken again for half a minute. Anything that publishes a verdict
            // before then can only be the driver's own floor deadline.
            f.stub.connTimeout = 30.seconds

            // Step in slices and record the first instant the verdict appears, rather than advancing
            // straight to the floor and asserting the clock is where advanceTimeBy just put it — which
            // is true whatever the driver did. Mutation-proven: with floorRemaining() forced to null,
            // `silentAt` stays Unmeasured here and the assertion below is what fails.
            var silentAt: Duration? = null
            val slice = 50.milliseconds
            while (currentTime.milliseconds < countMetAt + SILENT_PATH_MINIMUM_SILENCE * 2) {
                advanceTimeBy(slice)
                runCurrent()
                if (silentAt == null && f.driver.pathLiveness.value == PathLiveness.Silent) {
                    silentAt = currentTime.milliseconds
                }
            }

            val landed =
                assertNotNull(
                    silentAt,
                    "the run met both halves and nothing ever published the verdict, so detection is still " +
                        "pinned to quiche's next timer — #574's quantisation, not its trigger",
                )
            assertTrue(
                landed <= countMetAt + SILENT_PATH_MINIMUM_SILENCE + slice,
                "the verdict landed at $landed, but the run's floor was due at " +
                    "${countMetAt + SILENT_PATH_MINIMUM_SILENCE} — it waited for something other than the floor",
            )
        }

    /**
     * **The floor deadline disarms itself, even when quiche has no active path.**
     *
     * The regression guard for a livelock this branch shipped and adversarial review caught. The floor
     * deadline used to be cleared only by [QuicheDriver.publishActivePathLiveness], which runs from the
     * sampler — and the sampler bails when no path reports `active == true`. quiche clears that flag in
     * `on_failed_validation()` and picks a replacement only on the *next* `on_timeout`, so the deadline
     * stayed armed, recomputed as zero on every iteration, and the loop spun at 100% of a core. Worse,
     * the spin starved the `connOnTimeout` that would have restored the active path, so it did not
     * recover: measured at 105s of CPU with the test scheduler never going idle.
     *
     * An unanswered probe on cellular is the ordinary case for #574, so this was a phone-side livelock.
     * The test asserts the two things that distinguish fixed from broken: the body **terminates**, and
     * the verdict is published from the floor wake itself rather than from a stats read that cannot run.
     */
    @Test
    fun aFloorDeadlineIsDisarmedEvenWhenQuicheHasNoActivePath() =
        runTest {
            val f = Fixture(this, tick)
            startObserved(f)

            f.read(this, expiries = 0)
            var expiries = 0L
            repeat(SILENT_PATH_EXPIRY_THRESHOLD.toInt()) {
                expiries++
                f.read(this, expiries = expiries)
            }

            // quiche loses its active path — the state its own `on_failed_validation()` leaves behind —
            // and will not ask to be woken for half a minute.
            f.stub.connTimeout = 30.seconds
            f.stub.pathStats = listOf(pathStats(active = false, totalPtoCount = expiries))

            // Before the fix this never returns: the scheduler cannot go idle while the loop respins a
            // zero-length wait, so runTest's own timeout cannot fire either.
            advanceTimeBy(SILENT_PATH_MINIMUM_SILENCE * 2)
            runCurrent()

            assertEquals(
                PathLiveness.Silent,
                f.driver.pathLiveness.value,
                "the floor was reached with no active path to read, and the verdict was never published — " +
                    "so the deadline is still armed and the loop is spinning on it",
            )
        }

    /**
     * **Both halves of the conjunction, separately.**
     *
     * The expiry count is driven past [SILENT_PATH_EXPIRY_THRESHOLD] inside a few hundred milliseconds —
     * which is exactly what a fast path does, since RFC 9002's PTO cannot fall below quiche's 25ms
     * `max_ack_delay` and fifteen of them bottom out near 0.4s. A count-only rule calls that a dead
     * path and migrates; #385's 1.02s excursion lives in that window and
     * `MigrationSimTests.aBlipTheLengthOfThe385ExcursionCostsNoMigration` measures the migrations it
     * buys at 2ms and 10ms.
     *
     * So: past the count and short of the floor is [PathLiveness.Answering], and the same run once the
     * floor is met is [PathLiveness.Silent]. Neither assertion holds if either half is dropped.
     */
    @Test
    fun aRunPastTheExpiryThresholdIsNotSilentUntilTheTimeFloorIsMetToo() =
        runTest {
            val f = Fixture(this, tick)
            startObserved(f)

            // A baseline read on a healthy path, then one expiry per wake.
            f.read(this, expiries = 0)
            var expiries = 0L
            repeat((SILENT_PATH_EXPIRY_THRESHOLD + 1).toInt()) {
                expiries++
                f.read(this, expiries = expiries)
            }
            assertEquals(
                PathLiveness.Answering,
                f.driver.pathLiveness.value,
                "$expiries unanswered expiries inside ${tick * expiries.toInt()} was called a dead path. " +
                    "On a fast link that window is a blip — quiche's 25ms max_ack_delay floors the PTO, " +
                    "so the count alone is a ~0.4s wall-clock constant nobody chose, and #385's 1.02s " +
                    "excursion sits inside it",
            )

            // …and now the same run, once it has been going long enough to be evidence.
            while (currentTime.milliseconds < SILENT_PATH_MINIMUM_SILENCE + tick * 2) {
                expiries++
                f.read(this, expiries = expiries)
            }
            assertEquals(
                PathLiveness.Silent,
                f.driver.pathLiveness.value,
                "after $expiries unanswered expiries over ${currentTime.milliseconds} the path is still " +
                    "reported as answering, so the time floor has become a ceiling and #574's outage is " +
                    "back — nothing would ever trigger a data-plane migration",
            )
        }

    /**
     * **The other half is load-bearing too: time alone is not evidence.**
     *
     * On a slow path the time floor is met long before a run is convincing. At a 1s wake cadence the
     * floor has elapsed by the third unanswered expiry — and three is exactly "a few packets went
     * missing", the thing #385 says a data-plane trigger must be strictly stronger than. The count is
     * what makes it stronger, so past the floor and short of the count must still read as answering.
     *
     * A floor-only rule passes every other test in this file and every scenario in `MigrationSimTests`,
     * because at the sim's 120ms round trip the two halves happen to bind at the same expiry. This is
     * the only place they are separated in that direction — and the elapsed time is asserted from the
     * test scheduler's own clock first, so "still answering" cannot quietly mean "the floor had not
     * been reached either".
     */
    @Test
    fun theTimeFloorAloneIsNotEnoughWithoutTheExpiryCount() =
        runTest {
            val slowTick = 1.seconds
            val f = Fixture(this, slowTick)
            startObserved(f)

            f.read(this, expiries = 0) // baseline on a healthy path
            f.read(this, expiries = 1) // the run starts here…
            val runStartedAt = currentTime
            var expiries = 1L
            while (expiries < SILENT_PATH_EXPIRY_THRESHOLD - 1) {
                expiries++
                f.read(this, expiries = expiries)
            }

            val silentFor = (currentTime - runStartedAt).milliseconds
            assertTrue(
                silentFor >= SILENT_PATH_MINIMUM_SILENCE,
                "the run has only been going $silentFor, short of the $SILENT_PATH_MINIMUM_SILENCE " +
                    "floor, so the assertion below would pass on the floor rather than on the count",
            )
            assertEquals(
                PathLiveness.Answering,
                f.driver.pathLiveness.value,
                "$expiries unanswered expiries over $silentFor was called a dead path. Elapsed time on " +
                    "its own is not evidence — three missed expiries is 'a few packets went missing', " +
                    "which is precisely what #385 says this must be stronger than",
            )

            expiries++
            f.read(this, expiries = expiries)
            assertEquals(
                PathLiveness.Silent,
                f.driver.pathLiveness.value,
                "$expiries unanswered expiries over ${(currentTime - runStartedAt).milliseconds} is both " +
                    "halves of the threshold and still reads as answering, so nothing would ever trigger",
            )
        }

    /**
     * **A read from a different path is a baseline, never a delta.**
     *
     * Two counters that belong to two paths are not comparable, and the driver cannot rely on seeing
     * the switch: quiche performs it internally after a failed validation. If the new path's counter is
     * *higher*, the difference is a run the connection never had — a migration on no evidence.
     */
    @Test
    fun aRunDoesNotCarryAcrossAChangeOfActivePath() =
        runTest {
            val f = Fixture(this, tick)
            startObserved(f)

            // A long, well-established run on path 0 — past both halves of the threshold. Bounded by
            // the floor rather than by a count, so a sampler that never reports Silent fails the
            // assertion below instead of spinning the scheduler until `runTest` gives up — which is a
            // hang, not a result. (A count-shaped bound was the first attempt and landed one read
            // short of the floor, so the test failed for its own arithmetic rather than the code's.)
            f.read(this, expiries = 0)
            var expiries = 0L
            while (f.driver.pathLiveness.value != PathLiveness.Silent &&
                currentTime.milliseconds < SILENT_PATH_MINIMUM_SILENCE * 2
            ) {
                expiries++
                f.read(this, expiries = expiries, paths = 1)
            }
            assertEquals(
                PathLiveness.Silent,
                f.driver.pathLiveness.value,
                "$expiries unanswered expiries never reported the path as silent, so the rest of this " +
                    "test has nothing to carry across a path change",
            )

            // quiche moves the active path under us. Path 1's counter is higher still, so a driver
            // subtracting one from the other would see an even longer run.
            f.read(this, expiries = expiries + 100, activeIndex = 1, paths = 2)
            assertEquals(
                PathLiveness.Answering,
                f.driver.pathLiveness.value,
                "the run built on path 0 was carried onto path 1. `total_pto_count` is per path, so that " +
                    "difference is arithmetic across two unrelated counters — here it reports a run 100 " +
                    "expiries long on a path the connection has only just moved to",
            )
        }

    /**
     * **A counter that goes backwards is a baseline too** — the case the path index alone cannot catch,
     * because quiche may reuse a slab index for a different path (`make_room_for_new_path` evicts).
     *
     * quiche's counter is monotonic per path, so a regression can only mean the identity changed, and
     * the harm is that the *old* path's run is carried onto the new one: the connection arrives on a
     * fresh path already holding hundreds of unanswered expiries and migrates off it on the first
     * expiry it sees. That is the direction this asserts — the first cut of this test walked the run
     * upward instead and passed against the defect, because the `expired > 0` guard means a negative
     * delta cannot go negative, only fail to reset.
     *
     * The 1s cadence is what makes the two readings diverge: the carried run's clock started two ticks
     * earlier, so it is past [SILENT_PATH_MINIMUM_SILENCE] exactly when a correctly re-baselined run is
     * still at its first expiry.
     */
    @Test
    fun aCounterThatGoesBackwardsRebaselinesRatherThanCarryingTheOldPathsRun() =
        runTest {
            val f = Fixture(this, 1.seconds)
            startObserved(f)

            f.read(this, expiries = 0) // baseline
            f.read(this, expiries = 500) // a long run on whatever path this slot held
            f.read(this, expiries = 3) // …and now the slot holds a different path

            // One expiry on the new path. Carried, the run reads 501 and its clock has been going for
            // two ticks, which is both halves of the threshold; re-baselined, it is a run of one.
            f.read(this, expiries = 4)
            assertEquals(
                PathLiveness.Answering,
                f.driver.pathLiveness.value,
                "the first expiry seen on the path behind a reused slot was enough to call it dead — " +
                    "the previous path's run of 500, and its clock, were carried across the identity " +
                    "change. quiche's counter is monotonic per path, so a regression can only mean the " +
                    "identity changed, and the connection would migrate off a brand-new path at once",
            )

            // …and the proof that it is a *baseline* rather than a permanent disarm: a full fresh run
            // from here still reaches the threshold.
            var expiries = 4L
            while (f.driver.pathLiveness.value == PathLiveness.Answering && expiries < 4 + FRESH_RUN_LIMIT) {
                expiries++
                f.read(this, expiries = expiries)
            }
            assertEquals(
                PathLiveness.Silent,
                f.driver.pathLiveness.value,
                "after the counter regressed, a fresh run of ${expiries - 4} expiries over " +
                    "${1.seconds * (expiries - 4).toInt()} never reached the threshold, so the trigger " +
                    "is disarmed for the life of the connection rather than re-baselined",
            )
        }

    private companion object {
        const val PRIMARY_ADDR = 0x1000L
        const val PRIMARY_PORT = 44100
        const val PEER_ADDR = 0x2000L

        /** Bound on the fresh-run walk, so a disarmed trigger fails the assertion instead of hanging. */
        const val FRESH_RUN_LIMIT = 20

        /** Every field the sampler does not read is zero on purpose: it must read only these two. */
        fun pathStats(
            active: Boolean,
            totalPtoCount: Long,
        ) = QuicPathStats(
            validationState = 0,
            active = active,
            recv = 0,
            sent = 0,
            lost = 0,
            retrans = 0,
            totalPtoCount = totalPtoCount,
            rtt = Duration.ZERO,
            minRtt = Duration.ZERO,
            maxRtt = Duration.ZERO,
            rttvar = Duration.ZERO,
            cwnd = 0,
            sentBytes = 0,
            recvBytes = 0,
            lostBytes = 0,
            streamRetransBytes = 0,
            pmtu = 0,
            deliveryRate = 0,
        )

        /** The sampler never migrates, so a factory that is called at all is a test that has drifted. */
        val UnusedPathFactory =
            object : UdpChannelFactory {
                override val localEndpointSupport = LocalEndpointSupport.Bindable

                override suspend fun openPath(
                    localHost: String?,
                    localPort: Int,
                ): NewPath = error("PathLivenessTests never migrates")
            }
    }
}
