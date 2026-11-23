@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.currentThreadId
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/** Whether a worker is looking for work, parked, or woken from a park by another thread. */
internal enum class Rest {
    Busy,
    Parked,

    /** A waker moved it out of [Parked] and took it off the pool's parked count. */
    Woken,
}

/** Whether a worker's platform thread has started. */
private sealed interface WorkerThread {
    /** Not started: a worker of a pool with no thread of its own, or of a pool not started yet. */
    data object Unstarted : WorkerThread

    class Started(
        val thread: PoolThread,
    ) : WorkerThread
}

/** Which of a worker's steps was last reported stalled. */
private sealed interface Reported {
    data object None : Reported

    /** The step that began at [began]. */
    class Began(
        val began: Long,
    ) : Reported
}

/** What one [PoolWorker.turn] did. */
internal enum class Turned {
    /** It held a lane and ran its steps. */
    Ran,

    /** No lane was ready. */
    NoWork,
}

/**
 * One thread of a [HandlerPool]: it takes a ready lane, runs at most [HandlerPool.TURN_BUDGET] of its steps, and lets
 * it go, or gives it back to its own ring when steps remain. Other threads take from that ring too, so a lane this
 * worker gave back is not held up behind a step of another lane that blocks it. While a step runs, the worker shows
 * which and since when, so a loop's watch can report one that holds the thread too long.
 */
@Queue
internal class PoolWorker(
    private val pool: HandlerPool,
) {
    /** The claim a lane has while this worker holds it. */
    val held = LaneClaim.Held(this)

    /** The lanes this worker gave back or adopted from the any-thread queue: it pushes, any worker takes. */
    val ring = LaneRing()
    private val rest = AtomicReference(Rest.Busy)
    private val thread = AtomicReference<WorkerThread>(WorkerThread.Unstarted)

    /** The thread of the current turn, stored before the worker holds any lane, so a holder check never reads a stale one. */
    private val turnThread = AtomicLong(0L)

    /** The lane held now, written once a turn: whose connection a stalled step belongs to. */
    private val holding = AtomicReference<ReadyTake>(ReadyTake.None)

    /** The context of the step running now, [HandlerSite.NoStep] between steps; and when that step began. */
    private val stepContext = AtomicReference<CoroutineContext>(HandlerSite.NoStep)
    private val stepBegan = AtomicLong(0L)

    /** The step last reported stalled, so each step is reported once, by its watch or by its return. */
    private val reported = AtomicReference<Reported>(Reported.None)
    private val adopt: (ReadyLink) -> Unit = { ring.push(it.lane) }
    private val run: (Runnable, CoroutineContext) -> Unit = { block, context -> pool.runStep(this, block, context) }

    /** The pool thread's body: turns until the pool runs its lanes off its threads and no lane is ready. */
    fun runOn(handle: PoolThread) {
        thread.store(WorkerThread.Started(handle))
        while (true) {
            if (turn() == Turned.NoWork) {
                if (pool.runsOffPool()) break
                rest(handle)
            }
        }
        pool.threadEnded()
    }

    /** One turn on the calling thread: a test drives a worker of a driven pool with it. */
    fun turn(): Turned {
        turnThread.store(currentThreadId())
        return when (val found = pool.find(this)) {
            is HandlerLane -> {
                runLane(found)
                Turned.Ran
            }
            is ReadyTake.None -> Turned.NoWork
        }
    }

    /** Runs [lane], which this worker took from a ready queue, until it has no step or the turn's budget is spent. */
    fun runLane(lane: HandlerLane) {
        lane.hold(this)
        holding.store(lane)
        runHeld(lane)
        holding.store(ReadyTake.None)
    }

    private fun runHeld(lane: HandlerLane) {
        var ran = 0
        while (true) {
            ran += lane.runSteps(run, HandlerPool.TURN_BUDGET - ran)
            if (ran >= HandlerPool.TURN_BUDGET && lane.hasSteps()) return giveBack(lane)
            when (lane.release(this)) {
                Release.Released -> return
                Release.Reclaimed -> if (ran >= HandlerPool.TURN_BUDGET) return giveBack(lane)
            }
        }
    }

    /** This worker's thread: a step with [context] begins at [began]. */
    fun stepBegins(
        began: Long,
        context: CoroutineContext,
    ) {
        stepBegan.store(began)
        stepContext.store(context)
    }

    /** This worker's thread: the step returned. */
    fun stepEnded() = stepContext.store(HandlerSite.NoStep)

    /** Whether the step that began at [began] is reported now: true for the first caller only. */
    fun firstReportOf(began: Long): Boolean {
        val seen = reported.load()
        if (seen is Reported.Began && seen.began == began) return false
        return reported.compareAndSet(seen, Reported.Began(began))
    }

    /** Any thread, at [now]: the step this worker runs, if it has held its thread over [bound], is reported once. */
    fun watch(
        now: Long,
        bound: Long,
        reports: PoolReports,
    ) {
        val context = stepContext.load()
        if (context === HandlerSite.NoStep) return
        val began = stepBegan.load()
        val heldFor = now - began
        if (heldFor <= bound) return
        when (val lane = holding.load()) {
            is HandlerLane ->
                if (firstReportOf(began)) reports.report(HandlerObservation.Stalled(lane.connection, siteOf(context), heldFor, bound))
            is ReadyTake.None -> Unit
        }
    }

    /** Any thread: the lane this worker holds now, if any. */
    fun holding(): ReadyTake = holding.load()

    /** Whether the current thread runs this worker's turn. */
    fun onThisThread(): Boolean = turnThread.load() == currentThreadId()

    /** This worker's thread: every lane in the pool's any-thread queue moves to [ring]. */
    fun adoptAnywhere(anywhere: WorkInbox<ReadyLink>) = anywhere.drain(adopt)

    /** Any thread: wakes this worker if it is parked; answers whether it did. */
    fun wake(): Boolean {
        if (!rest.compareAndSet(Rest.Parked, Rest.Woken)) return false
        when (val now = thread.load()) {
            is WorkerThread.Started -> now.thread.unpark()
            is WorkerThread.Unstarted -> Unit
        }
        return true
    }

    private fun giveBack(lane: HandlerLane) {
        lane.unhold()
        pool.gaveBack(lane, this)
    }

    /**
     * Parks until woken. The worker is counted parked before it looks for work one last time, so a lane made ready after
     * that look finds it counted and wakes it, and one made ready before is found by the look.
     */
    private fun rest(handle: PoolThread) {
        rest.store(Rest.Parked)
        pool.parked()
        if (pool.hasWork() || pool.runsOffPool()) {
            if (rest.compareAndSet(Rest.Parked, Rest.Busy)) pool.unparked() else rest.store(Rest.Busy)
            return
        }
        while (rest.load() == Rest.Parked) handle.park()
        rest.store(Rest.Busy)
    }
}
