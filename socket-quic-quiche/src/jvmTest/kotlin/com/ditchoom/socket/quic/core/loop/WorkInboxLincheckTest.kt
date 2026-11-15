package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.LincheckBudget
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * [WorkInbox] with two producers and one consumer. The audit, after a final drain: every item ran exactly once,
 * and each producer's items ran in the order it pushed them.
 */
class WorkInboxLincheckTest {
    private class Item(
        val producer: Int,
        val sequence: Int,
    ) : InboxItem()

    private val inbox = WorkInbox<Item>()
    private val pushed = IntArray(PRODUCERS)
    private val lastRun = IntArray(PRODUCERS) { -1 }
    private val ran = AtomicInteger()
    private val violations = AtomicInteger()

    @Operation(nonParallelGroup = "producer0")
    fun pushFromFirst() = push(0)

    @Operation(nonParallelGroup = "producer1")
    fun pushFromSecond() = push(1)

    @Operation(nonParallelGroup = "consumer")
    fun drain() {
        inbox.drain(::run)
    }

    /** After every thread has finished: a last drain, then the count. */
    @Validate
    fun validate() {
        inbox.drain(::run)
        check(violations.get() == 0 && ran.get() == pushed.sum())
    }

    private fun push(producer: Int) {
        inbox.push(Item(producer, pushed[producer]++))
    }

    private fun run(item: Item) {
        if (item.sequence != lastRun[item.producer] + 1) violations.incrementAndGet()
        lastRun[item.producer] = item.sequence
        ran.incrementAndGet()
    }

    @Test
    fun everyItemRunsOnceInItsProducersOrder() {
        ModelCheckingOptions()
            .sequentialSpecification(WorkInboxSpec::class.java)
            .threads(3)
            .actorsPerThread(3)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(30))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))
            .check(this::class)
    }

    private companion object {
        const val PRODUCERS = 2
    }
}

class WorkInboxSpec {
    fun pushFromFirst() = Unit

    fun pushFromSecond() = Unit

    fun drain() = Unit
}
