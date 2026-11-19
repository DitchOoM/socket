package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LincheckBudget
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [ServerCloseCell] as a server's shutdown uses it: the loop asks; the server's close work either runs inside the
 * connection or, the connection having ended first, is told it is gone; whoever leaves the connection after collecting
 * its output marks the close out, the loop and a caller racing to. The audit: the stage never moves back, the close is
 * marked out by exactly one leaver and only after it ran, and a close that was gone counts as out without any leaver.
 */
abstract class ServerCloseCellModel(
    private val leaveWithoutCas: Boolean,
) {
    private val cell = ServerCloseCell()
    private val work = AtomicInteger()
    private val ran = AtomicInteger()
    private val gone = AtomicInteger()
    private val outs = AtomicInteger()
    private val violations = AtomicInteger()

    @Operation(nonParallelGroup = LOOP)
    fun loopAsks() = watched { cell.ask() }

    @Operation(nonParallelGroup = WORK)
    fun closeRunsInside() {
        if (cell.stage() == ServerCloseStage.Open || !work.compareAndSet(0, 1)) return
        watched { cell.ran() }
        ran.set(1)
    }

    @Operation(nonParallelGroup = WORK)
    fun connectionEndedFirst() {
        if (cell.stage() == ServerCloseStage.Open || !work.compareAndSet(0, 1)) return
        watched { cell.gone() }
        gone.set(1)
    }

    @Operation
    fun loopLeaves() = leave()

    @Operation
    fun callerLeaves() = leave()

    /** After every thread has finished. */
    @Validate
    fun validate() {
        val stage = cell.stage()
        check(violations.get() == 0 && outs.get() <= 1)
        check(outs.get() == 0 || ran.get() == 1)
        check(gone.get() == 0 || (stage == ServerCloseStage.Out && outs.get() == 0))
    }

    private fun leave() =
        watched {
            if (leaveWithoutCas) {
                if (cell.stage() == ServerCloseStage.Ran) outs.incrementAndGet()
            } else if (cell.out() == Moved.Yes) {
                outs.incrementAndGet()
            }
        }

    private inline fun watched(step: () -> Unit) {
        val before = cell.stage()
        step()
        if (cell.stage().ordinal < before.ordinal) violations.incrementAndGet()
    }

    protected fun options(): ModelCheckingOptions =
        ModelCheckingOptions()
            .sequentialSpecification(ServerCloseCellSpec::class.java)
            .threads(3)
            .actorsPerThread(2)
            .actorsBefore(1)
            .actorsAfter(1)
            .iterations(LincheckBudget.scaled(30))
            .invocationsPerIteration(LincheckBudget.scaled(3_000))

    private companion object {
        const val LOOP = "loop"
        const val WORK = "work"
    }
}

class ServerCloseCellLincheckTest : ServerCloseCellModel(leaveWithoutCas = false) {
    @Test
    fun theStageOnlyMovesForwardAndTheCloseIsMarkedOutOnceAfterItRan() {
        options().check(this::class)
    }
}

/** The control: leavers that read the stage instead of moving it by compare-and-set both claim the close. */
class ServerCloseCellLeavingWithoutCasLincheckTest : ServerCloseCellModel(leaveWithoutCas = true) {
    @Test
    fun theModelCatchesTwoLeaversBothClaimingTheClose() {
        assertFailsWith<LincheckAssertionError> { options().check(this::class) }
    }
}

class ServerCloseCellSpec {
    fun loopAsks() = Unit

    fun closeRunsInside() = Unit

    fun connectionEndedFirst() = Unit

    fun loopLeaves() = Unit

    fun callerLeaves() = Unit
}
