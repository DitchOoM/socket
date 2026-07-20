package com.ditchoom.socket.testkit.fault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FaultScheduleTests {
    @Test
    fun clean_isClean_andHasDefaultSeed() {
        assertTrue(FaultSchedule.CLEAN.isClean)
        assertTrue(FaultSchedule.CLEAN.faults.isEmpty())
        assertTrue(FaultSchedule.CLEAN.termination is Termination.None)
        assertEquals(FaultSchedule.DEFAULT_SEED, FaultSchedule.CLEAN.seed)
    }

    @Test
    fun emptyBuilder_isClean() {
        assertTrue(FaultSchedule { }.isClean)
    }

    @Test
    fun dropNth_isDeterministicIndexSelector() {
        val s = FaultSchedule { drop(nth = 3) }
        assertFalse(s.isClean)
        assertEquals(listOf(ScheduledFault(UnitSelector.Index(3), Fault.Drop)), s.faults)
    }

    @Test
    fun dropProbability_isDistinctOverload_fromDropNth() {
        // The two drops are disambiguated purely by argument type — Int index vs Double probability —
        // never by a nullable flag. This is the "no overloaded nullable" contract in action.
        val byIndex = FaultSchedule { drop(nth = 2) }.faults.single().selector
        val byProb = FaultSchedule { drop(probability = 0.25) }.faults.single().selector
        assertEquals(UnitSelector.Index(2), byIndex)
        assertEquals(UnitSelector.Probabilistic(0.25), byProb)
    }

    @Test
    fun fullDsl_buildsExpectedOrderedFaults() {
        val s =
            FaultSchedule {
                delay(20.milliseconds, jitter = 5.milliseconds)
                drop(nth = 3)
                dropEvery(n = 4, offset = 1)
                duplicate(nth = 5)
                corrupt(nth = 6, offset = 2, flipMask = 0x0F)
                reorder(window = 2)
                blackholeFrom(nth = 9)
                resetAfter(units = 12)
            }
        assertEquals(
            listOf(
                ScheduledFault(UnitSelector.All, Fault.Delay(20.milliseconds, 5.milliseconds)),
                ScheduledFault(UnitSelector.Index(3), Fault.Drop),
                ScheduledFault(UnitSelector.Every(4, 1), Fault.Drop),
                ScheduledFault(UnitSelector.Index(5), Fault.Duplicate(1.milliseconds)),
                ScheduledFault(UnitSelector.Index(6), Fault.Corrupt(2, 0x0F)),
                ScheduledFault(UnitSelector.All, Fault.Reorder(2)),
                ScheduledFault(UnitSelector.From(9), Fault.Drop),
            ),
            s.faults,
        )
        assertEquals(Termination.ResetAfterUnits(12), s.termination)
    }

    @Test
    fun seed_defaultsAndOverrides() {
        assertEquals(FaultSchedule.DEFAULT_SEED, FaultSchedule { drop(nth = 0) }.seed)
        assertEquals(42L, FaultSchedule(seed = 42) { drop(nth = 0) }.seed)
    }

    @Test
    fun equality_isStructural_includingSeed() {
        val a =
            FaultSchedule(seed = 1) {
                drop(nth = 3)
                reorder(window = 2)
            }
        val b =
            FaultSchedule(seed = 1) {
                drop(nth = 3)
                reorder(window = 2)
            }
        val differentSeed =
            FaultSchedule(seed = 2) {
                drop(nth = 3)
                reorder(window = 2)
            }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == differentSeed)
    }
}
