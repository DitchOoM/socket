package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.LincheckBudget
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [OwnerWord] as a connection uses it: a loop turn enters; callers, and the loop for a datagram that found a caller
 * inside, leave work in the inbox and enter when nobody is inside. The audit, after everything ran: nobody is inside,
 * no work is stranded, every item ran once, and two entrants were never inside together.
 */
abstract class OwnerWordModel(
    private val leaveIgnoringWork: Boolean,
) {
    private val word = OwnerWord()
    private val pending = AtomicInteger()
    private val left = AtomicInteger()
    private val ran = AtomicInteger()
    private val inside = AtomicInteger()
    private val violations = AtomicInteger()

    @Operation
    fun loopTurn() = enterAndRun(LOOP)

    /** A datagram that finds a caller inside is copied into work: the loop leaves work like a caller does. */
    @Operation
    fun loopLeavesCopiedDatagram() = leaveWorkAs(LOOP)

    @Operation
    fun callerOne() = leaveWorkAs(CALLER_ONE)

    @Operation
    fun callerTwo() = leaveWorkAs(CALLER_TWO)

    /** After every thread has finished. */
    @Validate
    fun validate() {
        check(violations.get() == 0 && word.owner() is Owner.Free && pending.get() == 0 && ran.get() == left.get())
    }

    private fun leaveWorkAs(me: Entrant) {
        left.incrementAndGet()
        pending.incrementAndGet()
        when (word.leaveWork()) {
            WorkLeft.ForHolder -> Unit
            WorkLeft.NobodyInside -> enterAndRun(me)
        }
    }

    private fun enterAndRun(me: Entrant) {
        when (word.tryEnter(me)) {
            Entry.Busy -> Unit
            Entry.Entered -> runInside(me)
        }
    }

    private fun runInside(me: Entrant) {
        while (true) {
            if (inside.incrementAndGet() != 1) violations.incrementAndGet()
            ran.addAndGet(pending.getAndSet(0))
            inside.decrementAndGet()
            if (leaveIgnoringWork) {
                word.tryLeave(me)
                return
            }
            if (word.tryLeave(me) === Leave.Left) return
        }
    }

    protected fun options(): ModelCheckingOptions =
        ModelCheckingOptions()
            .sequentialSpecification(OwnerWordSpec::class.java)
            .threads(3)
            .actorsPerThread(2)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(LincheckBudget.scaled(30))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))

    private companion object {
        val LOOP = Entrant.loopTurn(loop = 1, generation = 1)
        val CALLER_ONE = Entrant.caller(1)
        val CALLER_TWO = Entrant.caller(2)
    }
}

class OwnerWordLincheckTest : OwnerWordModel(leaveIgnoringWork = false) {
    @Test
    fun oneEntrantAtATimeAndNoWorkIsStranded() {
        options().check(this::class)
    }
}

/** The control: a holder that leaves without draining work that arrived. The model must catch it. */
class OwnerWordLeavingWorkBehindLincheckTest : OwnerWordModel(leaveIgnoringWork = true) {
    @Test
    fun theModelCatchesAHolderThatLeavesWorkBehind() {
        assertFailsWith<LincheckAssertionError> { options().check(this::class) }
    }
}

class OwnerWordSpec {
    fun loopTurn() = Unit

    fun loopLeavesCopiedDatagram() = Unit

    fun callerOne() = Unit

    fun callerTwo() = Unit
}
