package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.LincheckBudget
import com.ditchoom.socket.quic.QuicSessionId
import kotlinx.coroutines.Runnable
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test

/**
 * One lane of a driven pool of two workers, under every interleaving the model checker reaches: the lane's loop queues
 * a step in a pass (one thread, as a loop is), any thread queues one, and each worker takes a turn. The audit, after a
 * last turn at quiescence: no two steps overlapped, every step queued ran exactly once, the lane is idle and no ready
 * queue holds it. A lost wake-up leaves a step unrun; a broken claim overlaps two steps or runs one twice.
 */
class HandlerLaneLincheckTest {
    private val pool = HandlerPool(HandlerThreads.Driven(2), "lincheck", ::testContained)
    private val outbox = HandlerOutbox()
    private val lane = pool.lane(outbox, QuicSessionId("lane")).also { pool.enter() }
    private val queued = AtomicInteger()
    private val runs = AtomicIntegerArray(MAX_STEPS)
    private val inside = AtomicInteger()
    private val overlaps = AtomicInteger()

    private fun step(): Runnable {
        val id = queued.getAndIncrement()
        return Runnable {
            if (inside.incrementAndGet() != 1) overlaps.incrementAndGet()
            runs.incrementAndGet(id)
            inside.decrementAndGet()
        }
    }

    @Operation(nonParallelGroup = "loop")
    fun loopPass() {
        outbox.begin()
        lane.dispatch(EmptyCoroutineContext, step())
        outbox.end()
    }

    @Operation
    fun foreignDispatch() = lane.dispatch(EmptyCoroutineContext, step())

    @Operation(nonParallelGroup = "worker0")
    fun turnOfFirst() {
        pool.worker(0).turn()
    }

    @Operation(nonParallelGroup = "worker1")
    fun turnOfSecond() {
        pool.worker(1).turn()
    }

    @Validate
    fun validate() {
        while (pool.worker(0).turn() == Turned.Ran) Unit
        check(overlaps.get() == 0)
        for (i in 0 until queued.get()) check(runs.get(i) == 1)
        check(lane.claimNow() is LaneClaim.Idle)
        check(!pool.hasWork())
    }

    @Test
    fun noStepIsLostRunTwiceOrRunAlongsideAnother() {
        ModelCheckingOptions()
            .sequentialSpecification(HandlerLaneSpec::class.java)
            .threads(3)
            .actorsPerThread(3)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(40))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))
            .check(this::class)
    }

    private companion object {
        const val MAX_STEPS = 64
    }
}

class HandlerLaneSpec {
    fun loopPass() = Unit

    fun foreignDispatch() = Unit

    fun turnOfFirst() = Unit

    fun turnOfSecond() = Unit
}
