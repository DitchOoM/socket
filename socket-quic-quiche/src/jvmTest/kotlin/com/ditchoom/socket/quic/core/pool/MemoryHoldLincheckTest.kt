package com.ditchoom.socket.quic.core.pool

import com.ditchoom.socket.quic.LincheckBudget
import kotlinx.coroutines.Runnable
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * [MemoryHold] with a loop's founding claim and one connection's claim, each released twice from racing threads, and a
 * late acquire. The audit: the memory is freed exactly once, never while a claim that was not released still holds it,
 * and a claim acquired before the free keeps it held.
 */
class MemoryHoldLincheckTest {
    private val freed = AtomicInteger()
    private val hold = MemoryHold(Runnable { freed.incrementAndGet() })
    private val connection = (hold.acquire() as Hold.Held).claim
    private val violations = AtomicInteger()
    private val loopLetGo = AtomicInteger()
    private val connectionLetGo = AtomicInteger()

    @Operation
    fun loopStops() = loopReleases()

    @Operation
    fun loopStopsAgain() = loopReleases()

    @Operation
    fun connectionEnds() = connectionReleases()

    @Operation
    fun connectionEndsAgain() = connectionReleases()

    private fun loopReleases() {
        loopLetGo.set(1)
        hold.founding.release()
    }

    private fun connectionReleases() {
        connectionLetGo.set(1)
        connection.release()
    }

    @Operation
    fun aConnectionArrives() {
        when (val held = hold.acquire()) {
            is Hold.Held -> {
                if (freed.get() != 0) violations.incrementAndGet()
                held.claim.release()
            }
            is Hold.AlreadyFreed -> Unit
        }
    }

    @Validate
    fun validate() {
        val expected = if (loopLetGo.get() == 1 && connectionLetGo.get() == 1) 1 else 0
        check(violations.get() == 0 && freed.get() == expected)
    }

    @Test
    fun theMemoryIsFreedOnceAndOnlyWhenEveryClaimLetGo() {
        ModelCheckingOptions()
            .sequentialSpecification(MemoryHoldSpec::class.java)
            .threads(3)
            .actorsPerThread(2)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(30))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))
            .check(this::class)
    }
}

class MemoryHoldSpec {
    fun loopStops() = Unit

    fun loopStopsAgain() = Unit

    fun connectionEnds() = Unit

    fun connectionEndsAgain() = Unit

    fun aConnectionArrives() = Unit
}
