package com.ditchoom.socket.testkit.fault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ImpairmentEngineTests {
    private fun decisions(
        schedule: FaultSchedule,
        count: Int,
    ): List<UnitDecision> {
        val engine = ImpairmentEngine(schedule)
        return (0 until count).map { engine.decide() }
    }

    @Test
    fun clean_deliversEveryUnitVerbatim_noDelay() {
        decisions(FaultSchedule.CLEAN, 5).forEach {
            val d = assertDelivered(it)
            assertEquals(1, d.copies.size)
            assertEquals(Duration.ZERO, d.copies.single().afterDelay)
            assertTrue(
                d.copies
                    .single()
                    .edits
                    .isEmpty(),
            )
        }
    }

    @Test
    fun dropNth_dropsExactlyThatUnit() {
        val out = decisions(FaultSchedule { drop(nth = 2) }, 5)
        assertTrue(out[0] is UnitDecision.Delivered)
        assertTrue(out[1] is UnitDecision.Delivered)
        assertEquals(UnitDecision.Dropped, out[2])
        assertTrue(out[3] is UnitDecision.Delivered)
        assertTrue(out[4] is UnitDecision.Delivered)
    }

    @Test
    fun dropEvery_dropsTheArithmeticProgression() {
        val out = decisions(FaultSchedule { dropEvery(n = 3, offset = 1) }, 8)
        val dropped = out.indices.filter { out[it] == UnitDecision.Dropped }
        assertEquals(listOf(1, 4, 7), dropped)
    }

    @Test
    fun blackholeFrom_dropsEverythingFromIndexOnward() {
        val out = decisions(FaultSchedule { blackholeFrom(nth = 3) }, 6)
        assertTrue(out.take(3).all { it is UnitDecision.Delivered })
        assertTrue(out.drop(3).all { it == UnitDecision.Dropped })
    }

    @Test
    fun delay_appliesUniformlyToEveryUnit() {
        decisions(FaultSchedule { delay(20.milliseconds) }, 3).forEach {
            assertEquals(20.milliseconds, assertDelivered(it).copies.single().afterDelay)
        }
    }

    @Test
    fun duplicate_deliversTwoCopiesSpacedByDefault() {
        val out = decisions(FaultSchedule { duplicate(nth = 1) }, 3)
        assertEquals(1, assertDelivered(out[0]).copies.size)
        val dup = assertDelivered(out[1])
        assertEquals(2, dup.copies.size)
        assertEquals(Duration.ZERO, dup.copies[0].afterDelay)
        assertEquals(1.milliseconds, dup.copies[1].afterDelay)
    }

    @Test
    fun corrupt_attachesTheByteEditToThatUnitOnly() {
        val out = decisions(FaultSchedule { corrupt(nth = 0, offset = 2, flipMask = 0x0F) }, 2)
        assertEquals(listOf(ByteEdit(2, 0x0F)), assertDelivered(out[0]).copies.single().edits)
        assertTrue(
            assertDelivered(out[1])
                .copies
                .single()
                .edits
                .isEmpty(),
        )
    }

    @Test
    fun reorder_holdsAreBoundedByWindow_andDeterministicPerSeed() {
        val schedule = FaultSchedule(seed = 99) { reorder(window = 3) }
        val holdsA = decisions(schedule, 10).map { assertDelivered(it).copies.single().afterDelay }
        val holdsB = decisions(schedule, 10).map { assertDelivered(it).copies.single().afterDelay }
        assertEquals(holdsA, holdsB, "same seed must reproduce the exact reorder holds")
        holdsA.forEach { assertTrue(it in Duration.ZERO..3.milliseconds, "hold $it out of [0,3]ms window") }
        assertTrue(holdsA.any { it > Duration.ZERO }, "a window of 3 should produce at least one non-zero hold")
    }

    @Test
    fun probabilisticDrop_isReproducibleForAFixedSeed() {
        val schedule = FaultSchedule(seed = 7) { drop(probability = 0.5) }
        val a = decisions(schedule, 30).map { it is UnitDecision.Dropped }
        val b = decisions(schedule, 30).map { it is UnitDecision.Dropped }
        assertEquals(a, b, "same seed must reproduce the exact drop pattern")
        assertTrue(a.any { it } && a.any { !it }, "p=0.5 over 30 units should both drop and deliver some")
    }

    @Test
    fun deterministic_sameScheduleTwice_yieldsIdenticalDecisions() {
        val schedule =
            FaultSchedule(seed = 3) {
                delay(5.milliseconds, jitter = 5.milliseconds)
                drop(nth = 4)
                duplicate(nth = 2)
                reorder(window = 2)
            }
        assertEquals(decisions(schedule, 12), decisions(schedule, 12))
    }

    @Test
    fun terminatesAt_reflectsResetAfterUnits() {
        val engine = ImpairmentEngine(FaultSchedule { resetAfter(units = 3) })
        assertTrue(!engine.terminatesAt(2))
        assertTrue(engine.terminatesAt(3))
        assertTrue(engine.terminatesAt(4))
        assertTrue(!ImpairmentEngine(FaultSchedule.CLEAN).terminatesAt(100))
    }

    private fun assertDelivered(decision: UnitDecision): UnitDecision.Delivered {
        assertTrue(decision is UnitDecision.Delivered, "expected Delivered, was $decision")
        return decision
    }
}
