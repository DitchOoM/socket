@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.edge.HandlerClaim
import com.ditchoom.socket.quic.core.edge.HandlerSeat
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the edge's containment site does for a pool, for pools a test builds. */
internal fun testContained(code: Runnable): Caught =
    try {
        code.run()
        Caught.Clean
    } catch (failure: Throwable) {
        Caught.Threw(Thrown(failure))
    }

/**
 * The handler pool's claim, release and hand-off, driven step by step on one thread: a driven pool has no thread of
 * its own, so each interleaving below is the one the test names, not one a scheduler happened to pick.
 */
class HandlerPoolDrivenTests {
    private var now = 0L
    private val clock = LoopClock { Nanos(now) }
    private val pool = HandlerPool(HandlerThreads.Driven(2), "driven", ::testContained, BOUND.nanoseconds, clock)
    private val outbox = HandlerOutbox()
    private val worker = pool.worker(0)
    private val ran = ArrayList<String>()
    private val run: (Runnable, CoroutineContext) -> Unit = { block, context -> pool.runStep(worker, block, context) }

    private fun step(name: String) = Runnable { ran.add(name) }

    private fun lane(name: String = "c"): HandlerLane {
        pool.enter()
        return pool.lane(outbox, QuicSessionId(name))
    }

    /** As the loop does: a pass in which [lane] is resumed with [steps]. */
    private fun loopPass(
        lane: HandlerLane,
        vararg steps: String,
    ) {
        outbox.begin()
        for (s in steps) lane.dispatch(EmptyCoroutineContext, step(s))
        outbox.end()
    }

    @Test
    fun aStepTheLoopQueuesAfterTheHolderFoundNoneIsRunByTheHolderWhenItLetsGo() {
        val lane = lane()
        lane.dispatch(EmptyCoroutineContext, step("first"))
        assertSame(lane, pool.find(worker))
        lane.hold(worker)
        assertEquals(1, lane.runSteps(run, HandlerPool.TURN_BUDGET))
        // The holder saw no step. The loop queues one now and ends its pass: its claim fails, as the lane is held.
        loopPass(lane, "late")
        assertIs<LaneClaim.Held>(lane.claimNow())
        assertTrue(!pool.hasWork(), "the lane is in no ready queue: only the holder's second look can find the step")
        assertEquals(Release.Reclaimed, lane.release(worker))
        assertEquals(1, lane.runSteps(run, HandlerPool.TURN_BUDGET))
        assertEquals(Release.Released, lane.release(worker))
        assertEquals(listOf("first", "late"), ran)
    }

    @Test
    fun aStepTheLoopQueuesAfterTheHolderLetGoIsClaimedByTheLoopsPass() {
        val lane = lane()
        lane.dispatch(EmptyCoroutineContext, step("first"))
        assertEquals(Turned.Ran, worker.turn())
        assertIs<LaneClaim.Idle>(lane.claimNow())
        loopPass(lane, "late")
        assertIs<LaneClaim.Ready>(lane.claimNow())
        assertTrue(pool.hasWork())
        assertEquals(Turned.Ran, worker.turn())
        assertEquals(Turned.NoWork, worker.turn())
        assertEquals(listOf("first", "late"), ran)
    }

    @Test
    fun aStepFromAnotherThreadWhileTheLaneIsHeldIsRunBeforeTheHolderLetsGo() {
        val lane = lane()
        lane.dispatch(EmptyCoroutineContext, step("first"))
        assertSame(lane, pool.find(worker))
        lane.hold(worker)
        lane.runSteps(run, HandlerPool.TURN_BUDGET)
        lane.dispatch(EmptyCoroutineContext, step("foreign"))
        assertEquals(1, lane.pending(), "a step queued from another thread is counted")
        assertTrue(!pool.hasWork(), "a held lane is not queued again")
        assertEquals(Release.Reclaimed, lane.release(worker))
        lane.runSteps(run, HandlerPool.TURN_BUDGET)
        assertEquals(Release.Released, lane.release(worker))
        assertEquals(listOf("first", "foreign"), ran)
        assertEquals(0, lane.pending())
    }

    @Test
    fun aPassMakesEachTouchedLaneReadyOnceHoweverManyStepsItQueued() {
        val lanes = List(LANES) { lane("c$it") }
        outbox.begin()
        for (round in 0 until 3) for (l in lanes) l.dispatch(EmptyCoroutineContext, step("$l/$round"))
        outbox.end()
        var turns = 0
        while (worker.turn() == Turned.Ran) turns++
        assertEquals(LANES, turns, "one claim per lane per pass")
        assertEquals(LANES * 3, ran.size)
    }

    @Test
    fun aLaneIsNotTakenByAnotherWorkerWhileItsHolderRunsItsStep() {
        val lane = lane()
        val other = pool.worker(1)
        var inside = 0
        var overlapped = 0
        lane.dispatch(
            EmptyCoroutineContext,
            Runnable {
                inside++
                // A second step of the same connection arrives while this one runs: nobody else may take the lane.
                lane.dispatch(
                    EmptyCoroutineContext,
                    Runnable {
                        if (++inside > 1) overlapped++
                        inside--
                    },
                )
                assertEquals(Turned.NoWork, other.turn())
                inside--
            },
        )
        assertEquals(Turned.Ran, worker.turn())
        assertEquals(0, overlapped)
        assertEquals(0, inside)
        assertEquals(Turned.NoWork, other.turn())
    }

    @Test
    fun aTurnRunsAtMostItsBudgetAndGivesTheLaneBackWithTheRest() {
        val lane = lane()
        val total = HandlerPool.TURN_BUDGET + REST
        repeat(total) { lane.dispatch(EmptyCoroutineContext, step("s$it")) }
        assertEquals(Turned.Ran, worker.turn())
        assertEquals(HandlerPool.TURN_BUDGET, ran.size)
        assertIs<LaneClaim.Ready>(lane.claimNow())
        assertEquals(Turned.Ran, pool.worker(1).turn(), "a lane given back is taken by any worker")
        assertEquals(total, ran.size)
        assertIs<LaneClaim.Idle>(lane.claimNow())
    }

    @Test
    fun aStepThatHoldsItsThreadPastTheBoundIsReportedOnceWithItsSite() {
        val seen = ArrayList<HandlerObservation>()
        pool.observe { seen.add(it) }
        val lane = lane("slow")
        val site = HandlerSite.Stream(QuicStreamId(4))
        lane.dispatch(site, Runnable { now += BOUND + 1 })
        lane.dispatch(EmptyCoroutineContext, Runnable { now += BOUND })
        worker.turn()
        val stalled = assertIs<HandlerObservation.Stalled>(seen.single())
        assertEquals("slow", stalled.connection.hex)
        assertSame(site, stalled.site)
        assertEquals(BOUND + 1, stalled.heldNanos)
    }

    @Test
    fun aStepThatNeverReturnsIsReportedByTheLoopsWatchWhileItRunsAndOnlyOnce() {
        val seen = ArrayList<HandlerObservation>()
        pool.observe { seen.add(it) }
        val lane = lane("blocked")
        val site = HandlerSite.Stream(QuicStreamId(8))
        lane.dispatch(
            site,
            Runnable {
                // While this step runs, time passes and the loop makes passes: the first pass past the bound reports it.
                now += BOUND / 2
                loopPass(lane("other"))
                assertTrue(seen.isEmpty(), "under the bound nothing is reported")
                now += BOUND
                loopPass(lane("other"))
                loopPass(lane("other"))
                assertEquals(1, seen.size, "seen by the watch, once")
            },
        )
        worker.turn()
        val stalled = assertIs<HandlerObservation.Stalled>(seen.single(), "its return does not report it again")
        assertEquals("blocked", stalled.connection.hex)
        assertSame(site, stalled.site)
    }

    @Test
    fun aStepThatThrowsIsReportedAndTheWorkerGoesOnWithTheLaneLetGo() {
        val seen = ArrayList<HandlerObservation>()
        pool.observe { seen.add(it) }
        val lane = lane("throws")
        lane.dispatch(EmptyCoroutineContext, Runnable { throw IllegalStateException() })
        lane.dispatch(EmptyCoroutineContext, step("after"))
        assertEquals(Turned.Ran, worker.turn())
        assertIs<IllegalStateException>(assertIs<HandlerObservation.StepThrew>(seen.single()).thrown.throwable)
        assertEquals(listOf("after"), ran)
        assertIs<LaneClaim.Idle>(lane.claimNow())
    }

    @Test
    fun aSinkThatThrowsLosesItsObservationAndNothingElse() {
        pool.observe { throw IllegalStateException() }
        val lane = lane()
        lane.dispatch(EmptyCoroutineContext, Runnable { now += BOUND + 1 })
        lane.dispatch(EmptyCoroutineContext, step("after"))
        assertEquals(Turned.Ran, worker.turn())
        assertEquals(listOf("after"), ran)
        assertIs<LaneClaim.Idle>(lane.claimNow())
    }

    @Test
    fun aRefusedClaimLeavesTheSeatAsItWasSoTheNextCallIsRefusedToo() {
        val seat = HandlerSeat(pool, outbox, QuicSessionId("seat"))
        pool.stop()
        assertEquals(HandlerClaim.Refused, seat.claim())
        assertEquals(HandlerClaim.Refused, seat.claim(), "not Taken: no call handles the streams")
    }

    @Test
    fun aSecondClaimIsTakenAndTheFirstKeepsItsLane() {
        val seat = HandlerSeat(pool, outbox, QuicSessionId("seat"))
        assertIs<HandlerClaim.Claimed>(seat.claim())
        assertEquals(HandlerClaim.Taken, seat.claim())
        assertEquals(Uses.None.added(), assertIs<PoolLife.Running>(pool.life()).uses, "a taken claim does not enter the pool")
    }

    @Test
    fun aLeaveWithoutItsEnterChangesNothing() {
        pool.enter()
        pool.leave()
        pool.leave()
        assertEquals(Uses.None, assertIs<PoolLife.Running>(pool.life()).uses)
        pool.enter()
        pool.stop()
        assertIs<PoolLife.Stopping>(pool.life())
        pool.leave()
        assertIs<PoolLife.Stopped>(pool.life())
        pool.leave()
        assertIs<PoolLife.Stopped>(pool.life())
    }

    @Test
    fun thePoolStopsOnlyOnceNoConnectionRunsHandlersAndThenRunsALateStepOffThePool() {
        val lane = lane()
        pool.stop()
        assertIs<PoolLife.Stopping>(pool.life())
        assertEquals(Entered.Refused, pool.enter())
        pool.leave()
        assertIs<PoolLife.Stopped>(pool.life())
        // Nothing of the pool runs it now: the step is not lost, it runs on the default dispatcher.
        val lateRan = AtomicInt(0)
        lane.dispatch(EmptyCoroutineContext, Runnable { lateRan.store(1) })
        assertEquals(Turned.NoWork, worker.turn())
        val until = TimeSource.Monotonic.markNow() + 10.seconds
        while (lateRan.load() == 0) assertTrue(until.hasNotPassedNow(), "the late step never ran")
    }

    private companion object {
        const val BOUND = 1_000L
        const val LANES = 8
        const val REST = 10
    }
}
