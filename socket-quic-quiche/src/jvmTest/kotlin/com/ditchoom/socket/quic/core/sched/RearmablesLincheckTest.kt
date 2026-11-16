package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.LincheckBudget
import com.ditchoom.socket.quic.Rearmables
import com.ditchoom.socket.quic.Wanted
import kotlinx.coroutines.Runnable
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

/**
 * [Rearmables]' lock-free protocol, model checked as a connection's owner re-arms and disarms its one deadline on one
 * thread while the loop fires and looks for the next deadline on another, at times it is given (the virtual clock).
 *
 * An audit, not a linearizability specification: the owner's last word must be what the loop ends with. After every
 * thread finished, a pass at a time past every arming sees everything: a deadline whose owner last armed it has fired
 * since that arming and is armed no more (no arming lost in the post, none stranded in the heap), one whose owner last
 * disarmed it does not fire in that pass and has left the heap, and no fire ever ran before the time it was armed for.
 */
class RearmablesLincheckTest {
    private val loop = Rearmables()
    private val fires = AtomicInteger()
    private val violations = AtomicInteger()

    /** The time the owner's latest arming asked for, or [NOT_ARMED]. Written by the owner only. */
    private val latest = AtomicLong(NOT_ARMED)

    /** The earliest time any arming has asked for: no fire may run before it. Written by the owner only. */
    private val earliest = AtomicLong(Long.MAX_VALUE)

    /** Written and read by the loop's actor only (one non-parallel group). */
    private var now = 0L

    private val deadline =
        loop.make(
            Runnable {
                fires.incrementAndGet()
                if (earliest.get() > now) violations.incrementAndGet()
            },
        )

    @Operation(nonParallelGroup = "owner")
    fun arm10(): Unit = arm(10)

    @Operation(nonParallelGroup = "owner")
    fun arm20(): Unit = arm(20)

    @Operation(nonParallelGroup = "owner")
    fun arm40(): Unit = arm(40)

    @Operation(nonParallelGroup = "owner")
    fun disarm() {
        latest.set(NOT_ARMED)
        deadline.disarm()
    }

    @Operation(nonParallelGroup = "loop")
    fun fireDue15() = fireDue(15)

    @Operation(nonParallelGroup = "loop")
    fun fireDue30() = fireDue(30)

    @Operation(nonParallelGroup = "loop")
    fun nextDue() {
        loop.nextDue()
    }

    /** After every thread: one pass that sees everything, then the audit. True when it holds. */
    @Operation(nonParallelGroup = "loop")
    fun audit(): Boolean {
        val before = fires.get()
        fireDue(100)
        val inLastPass = fires.get() - before
        when (latest.get()) {
            NOT_ARMED -> if (inLastPass != 0) violations.incrementAndGet()
            else -> if (deadline.wanted() != Wanted.None) violations.incrementAndGet()
        }
        if (loop.size != 0) violations.incrementAndGet()
        return violations.get() == 0
    }

    private fun arm(due: Long) {
        latest.set(due)
        earliest.accumulateAndGet(due, ::minOf)
        deadline.arm(due)
    }

    private fun fireDue(at: Long) {
        now = at
        loop.fireDue(at)
    }

    @Test
    fun theLoopEndsWithTheOwnersLastWordAndNeverFiresEarly() {
        ModelCheckingOptions()
            .sequentialSpecification(RearmablesSpec::class.java)
            .addCustomScenario {
                parallel {
                    thread {
                        actor(RearmablesLincheckTest::arm10)
                        actor(RearmablesLincheckTest::arm40)
                        actor(RearmablesLincheckTest::arm20)
                    }
                    thread {
                        actor(RearmablesLincheckTest::fireDue15)
                        actor(RearmablesLincheckTest::nextDue)
                        actor(RearmablesLincheckTest::fireDue30)
                    }
                }
                post { actor(RearmablesLincheckTest::audit) }
            }.addCustomScenario {
                parallel {
                    thread {
                        actor(RearmablesLincheckTest::arm10)
                        actor(RearmablesLincheckTest::disarm)
                        actor(RearmablesLincheckTest::arm20)
                        actor(RearmablesLincheckTest::disarm)
                    }
                    thread {
                        actor(RearmablesLincheckTest::fireDue15)
                        actor(RearmablesLincheckTest::fireDue30)
                    }
                }
                post { actor(RearmablesLincheckTest::audit) }
            }.addCustomScenario {
                parallel {
                    thread {
                        actor(RearmablesLincheckTest::arm40)
                        actor(RearmablesLincheckTest::arm10)
                        actor(RearmablesLincheckTest::disarm)
                        actor(RearmablesLincheckTest::arm10)
                    }
                    thread {
                        actor(RearmablesLincheckTest::nextDue)
                        actor(RearmablesLincheckTest::fireDue15)
                        actor(RearmablesLincheckTest::fireDue30)
                    }
                }
                post { actor(RearmablesLincheckTest::audit) }
            }.threads(2)
            .actorsPerThread(4)
            .actorsAfter(1)
            // Only the scenarios above: a generated one could run the audit beside an arming, which it is not for.
            .iterations(0)
            .invocationsPerIteration(LincheckBudget.scaled(10_000))
            .check(this::class)
    }

    private companion object {
        const val NOT_ARMED = -1L
    }
}

/** What every operation promises to a caller: nothing observable but that [RearmablesLincheckTest.audit] holds. */
class RearmablesSpec {
    fun arm10() = Unit

    fun arm20() = Unit

    fun arm40() = Unit

    fun disarm() = Unit

    fun fireDue15() = Unit

    fun fireDue30() = Unit

    fun nextDue() = Unit

    fun audit(): Boolean = true
}
