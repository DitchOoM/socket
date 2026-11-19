package com.ditchoom.socket.quic.core.pool

import com.ditchoom.socket.quic.LincheckBudget
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * [FixedPool] under take and give from several threads, including the take, take, give-back-in-another-order
 * interleavings that break an unstamped stack. The audit: no item was ever held by two takers, and every item
 * is back in the pool.
 */
class FixedPoolLincheckTest {
    private class Item(
        index: Int,
    ) : Pooled(index)

    private val pool = FixedPool(SIZE) { Item(it) }
    private val holders = Array(SIZE) { AtomicInteger() }
    private val violations = AtomicInteger()

    @Operation
    fun takeOneAndGiveBack() {
        val first = pool.tryTake()
        if (first is Item) hold(first)
        if (first is Item) pool.give(first)
    }

    @Operation
    fun takeTwoAndGiveBackReversed() {
        val first = pool.tryTake()
        val second = pool.tryTake()
        if (first is Item) hold(first)
        if (second is Item) hold(second)
        if (first is Item) pool.give(first)
        if (second is Item) pool.give(second)
    }

    /** After every thread has finished. */
    @Validate
    fun validate() {
        check(violations.get() == 0 && pool.available() == SIZE)
    }

    private fun hold(item: Item) {
        if (holders[item.index].incrementAndGet() != 1) violations.incrementAndGet()
        holders[item.index].decrementAndGet()
    }

    @Test
    fun noItemIsTakenTwiceAndNoneIsLost() {
        ModelCheckingOptions()
            .sequentialSpecification(FixedPoolSpec::class.java)
            .threads(3)
            .actorsPerThread(2)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(30))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))
            .check(this::class)
    }

    private companion object {
        const val SIZE = 2
    }
}

class FixedPoolSpec {
    fun takeOneAndGiveBack() = Unit

    fun takeTwoAndGiveBackReversed() = Unit
}
