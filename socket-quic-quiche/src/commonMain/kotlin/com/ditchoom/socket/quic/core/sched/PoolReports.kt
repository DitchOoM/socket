@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.life.Tier
import com.ditchoom.socket.quic.core.life.Tiers
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.reportOnThisThread
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * A pool's observation stream and the steps it times: a step is contained, timed against [stallBound], and reported
 * stalled once, by its own return or by a loop's watch while it still runs. A sink that throws loses that observation.
 */
@Published
internal class PoolReports(
    stallBound: Duration,
    private val clock: LoopClock,
    private val contain: (Runnable) -> Caught,
) {
    private val boundNanos = stallBound.inWholeNanoseconds
    private val sink = AtomicReference<(HandlerObservation) -> Unit>(IGNORE)

    /** Any thread: every later observation goes to [to]. */
    fun observe(to: (HandlerObservation) -> Unit) = sink.store(to)

    fun report(observation: HandlerObservation) {
        val to = sink.load()
        contain(Runnable { to(observation) })
    }

    /**
     * [worker] runs one step. What it throws is contained: an exception is reported, and an `Error` is reported and also
     * told to the thread's uncaught handler, as the process tier is.
     */
    fun runStep(
        worker: PoolWorker,
        block: Runnable,
        context: CoroutineContext,
    ) {
        val began = clock.now().value
        worker.stepBegins(began, context)
        when (val caught = contain(block)) {
            is Caught.Clean -> Unit
            is Caught.Threw -> stepThrew(worker, context, caught.thrown)
        }
        worker.stepEnded()
        val held = clock.now().value - began
        if (held > boundNanos && worker.firstReportOf(began)) {
            when (val lane = worker.holding()) {
                is HandlerLane -> report(HandlerObservation.Stalled(lane.connection, siteOf(context), held, boundNanos))
                is ReadyTake.None -> Unit
            }
        }
    }

    /** The stall watch over [workers]: each running step held over the bound is reported once. */
    fun watch(workers: Array<PoolWorker>) {
        val now = clock.now().value
        for (i in workers.indices) workers[i].watch(now, boundNanos, this)
    }

    private fun stepThrew(
        worker: PoolWorker,
        context: CoroutineContext,
        thrown: Thrown,
    ) {
        when (val lane = worker.holding()) {
            is HandlerLane -> report(HandlerObservation.StepThrew(lane.connection, siteOf(context), thrown))
            is ReadyTake.None -> Unit
        }
        when (Tiers.of(thrown.throwable)) {
            Tier.Connection -> Unit
            Tier.Process -> reportOnThisThread(thrown.throwable)
        }
    }

    private companion object {
        val IGNORE: (HandlerObservation) -> Unit = { }
    }
}
