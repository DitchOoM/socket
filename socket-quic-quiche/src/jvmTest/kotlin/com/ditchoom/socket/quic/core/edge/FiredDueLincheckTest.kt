package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.LincheckBudget
import com.ditchoom.socket.quic.Wanted
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

/**
 * [FiredDue] between the loop, which fires a slot's armings, and whoever is inside, which runs the queued expiry and
 * lends the slot again. The audit: the expiry is never queued twice at once (the inbox link would be corrupted), an
 * expiry that runs expires only an arming that did fall due and is still held, and after every thread one last run of
 * a queued expiry leaves nothing queued.
 */
class FiredDueLincheckTest {
    private val record = FiredDue()

    /** What the slot is armed for; the inside actor's own. */
    private val armed = AtomicLong(Wanted.None.word())

    /** Expiries queued and not yet run. */
    private val queued = AtomicInteger()
    private val fired =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<Long>()
    private val violations = AtomicInteger()

    @Operation(nonParallelGroup = "loop")
    fun fire10() = fire(10)

    @Operation(nonParallelGroup = "loop")
    fun fire20() = fire(20)

    @Operation(nonParallelGroup = "inside")
    fun hold10() = armed.set(10)

    @Operation(nonParallelGroup = "inside")
    fun hold20() = armed.set(20)

    @Operation(nonParallelGroup = "inside")
    fun letGo() = armed.set(Wanted.None.word())

    @Operation(nonParallelGroup = "inside")
    fun runExpiry() {
        if (queued.get() == 0) return
        queued.decrementAndGet()
        val holding = Wanted.ofWord(armed.get())
        when (record.take(holding)) {
            Expiry.Due -> holding.fold(none = { violations.incrementAndGet() }, at = { if (it !in fired) violations.incrementAndGet() })
            Expiry.Stale -> Unit
        }
    }

    @Validate
    fun validate() {
        runExpiry()
        check(violations.get() == 0 && queued.get() == 0)
    }

    private fun fire(due: Long) {
        fired.add(due)
        if (record.fell(due) && queued.incrementAndGet() > 1) violations.incrementAndGet()
    }

    @Test
    fun theExpiryIsQueuedOnceAndExpiresOnlyAnArmingThatFellDueAndIsStillHeld() {
        ModelCheckingOptions()
            .threads(2)
            .actorsPerThread(4)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(50))
            .invocationsPerIteration(LincheckBudget.scaled(2_000))
            .sequentialSpecification(FiredDueSpec::class.java)
            .check(this::class)
    }
}

class FiredDueSpec {
    fun fire10() = Unit

    fun fire20() = Unit

    fun hold10() = Unit

    fun hold20() = Unit

    fun letGo() = Unit

    fun runExpiry() = Unit
}
