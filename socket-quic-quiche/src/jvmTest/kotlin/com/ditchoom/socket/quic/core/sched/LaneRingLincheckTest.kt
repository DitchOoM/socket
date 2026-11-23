package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.LincheckBudget
import com.ditchoom.socket.quic.QuicSessionId
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test

/**
 * [LaneRing] against a FIFO queue: one owner pushes, two threads take, and the ring starts at two slots so it grows and
 * clears under the checker. Every history must be one a sequential queue could produce.
 */
class LaneRingLincheckTest {
    private val pool = HandlerPool(HandlerThreads.Driven(1), "ring", ::testContained)
    private val outbox = HandlerOutbox()
    private val lanes = List(MAX_PUSHES) { pool.lane(outbox, QuicSessionId("l$it")) }
    private val ring = LaneRing(initial = 2)
    private var pushed = 0

    @Operation(nonParallelGroup = "owner")
    fun push(): Int {
        val index = pushed++
        ring.push(lanes[index])
        return index
    }

    @Operation
    fun take(): Int =
        when (val taken = ring.take()) {
            is HandlerLane -> lanes.indexOf(taken)
            is ReadyTake.None -> NONE
        }

    @Test
    fun takesAreThoseOfAQueue() {
        ModelCheckingOptions()
            .sequentialSpecification(LaneRingSpec::class.java)
            .threads(3)
            .actorsPerThread(4)
            .actorsBefore(0)
            .actorsAfter(1)
            .iterations(LincheckBudget.scaled(40))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))
            .check(this::class)
    }

    private companion object {
        const val MAX_PUSHES = 32
        const val NONE = -1
    }
}

class LaneRingSpec {
    private val queue = ArrayDeque<Int>()
    private var pushed = 0

    fun push(): Int {
        queue.addLast(pushed)
        return pushed++
    }

    fun take(): Int = queue.removeFirstOrNull() ?: -1
}
