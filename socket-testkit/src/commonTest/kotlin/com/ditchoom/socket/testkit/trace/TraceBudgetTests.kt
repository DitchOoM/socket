package com.ditchoom.socket.testkit.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The 75-hour walk both rigs actually run must fit inside its own budget with room to spare: the
 * flat 512 MB it replaces died at hour 65 on Android (#597) and would have died at hour ~63 on iOS
 * (#604), each time taking the tail of the run — where a drive's handoffs are — out of replay.
 */
class TraceBudgetTests {
    private val measuredIosBytesPerHour = 201_379_680L / 25

    @Test
    fun theRealRunFitsInsideItsOwnBudget() {
        val budget = TraceBudget.forWalk(minutes = 4500, echoInterval = 250.milliseconds, lanes = 1)

        assertEquals(1_080_000L, budget.plannedExchanges)
        assertEquals(1182L, budget.megabytes)
        assertTrue(
            budget.bytes >= 75 * measuredIosBytesPerHour,
            "75 h at the measured iOS rate is ${75 * measuredIosBytesPerHour} bytes; the budget is ${budget.bytes}",
        )
        assertTrue(budget.bytes > 512L * 1024L * 1024L, "the flat 512 MB this replaces was not enough")
    }

    @Test
    fun aShortRunStillGetsTheFloor() {
        assertEquals(TraceBudget.FLOOR_MB, TraceBudget.forWalk(minutes = 2, echoInterval = 250.milliseconds, lanes = 1).megabytes)
    }

    @Test
    fun aRunCannotOutgrowTheCeiling() {
        assertEquals(TraceBudget.CEILING_MB, TraceBudget.forWalk(minutes = 100_000, echoInterval = 100.milliseconds, lanes = 1).megabytes)
    }

    @Test
    fun theLineNamesBothInputs() {
        assertEquals(
            "TRACE-BUDGET mb=1182 plannedExchanges=1080000 lanes=1",
            TraceBudget.forWalk(minutes = 4500, echoInterval = 250.milliseconds, lanes = 1).line,
        )
    }

    @Test
    fun anOverrideChangesTheCeilingAndNothingElse() {
        val overridden = TraceBudget.forWalk(minutes = 4500, echoInterval = 250.milliseconds, lanes = 1).withMegabytes(2000)

        assertEquals(2000L, overridden.megabytes)
        assertEquals(1_080_000L, overridden.plannedExchanges)
    }

    /** Each lane runs its own echo loop for the whole walk, so the default two-lane walk plans twice the trace. */
    @Test
    fun twoLanesDoubleTheBudgetAndStayUnderTheCeiling() {
        val budget = TraceBudget.forWalk(minutes = 4500, echoInterval = 250.milliseconds, lanes = 2)

        assertEquals(2_160_000L, budget.plannedExchanges)
        assertEquals(2364L, budget.megabytes)
        assertTrue(budget.megabytes < TraceBudget.CEILING_MB, "${budget.megabytes} MB")
        assertEquals("TRACE-BUDGET mb=2364 plannedExchanges=2160000 lanes=2", budget.line)
    }
}
