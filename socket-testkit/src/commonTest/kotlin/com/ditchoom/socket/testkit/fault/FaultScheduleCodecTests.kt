package com.ditchoom.socket.testkit.fault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Round-trip + robustness contract for [FaultScheduleCodec] (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §5):
 * `decode(encode(s)) == s` for every schedule shape the DSL and the raw model can express, and a
 * malformed document fails loudly rather than yielding a nonsense schedule.
 */
class FaultScheduleCodecTests {
    private fun assertRoundTrips(schedule: FaultSchedule) {
        val encoded = FaultScheduleCodec.encode(schedule)
        assertEquals(schedule, FaultScheduleCodec.decode(encoded), "round-trip mismatch for:\n$encoded")
        // decode is idempotent under re-encode too (encode is canonical).
        assertEquals(encoded, FaultScheduleCodec.encode(FaultScheduleCodec.decode(encoded)))
    }

    @Test
    fun clean_roundTrips() = assertRoundTrips(FaultSchedule.CLEAN)

    @Test
    fun everyDslVerb_roundTrips() =
        assertRoundTrips(
            FaultSchedule(seed = 42) {
                delay(20.milliseconds, jitter = 5.milliseconds)
                drop(nth = 3)
                dropEvery(n = 4, offset = 1)
                drop(probability = 0.25)
                duplicate(nth = 2, spacing = 3.milliseconds)
                corrupt(nth = 0, offset = 7, flipMask = 0xA5)
                reorder(window = 2)
                blackholeFrom(nth = 9)
            },
        )

    @Test
    fun rangeSelectorAndReset_roundTrip() {
        // Range + resetAfter aren't reachable through a single DSL verb pairing, so build the model
        // directly to prove the codec covers the whole sealed surface, not just DSL output.
        val schedule =
            FaultSchedule(
                faults =
                    listOf(
                        ScheduledFault(UnitSelector.Range(2, 5), Fault.Drop),
                        ScheduledFault(UnitSelector.All, Fault.Delay(1.milliseconds)),
                    ),
                termination = Termination.ResetAfterUnits(4),
                seed = -7L,
            )
        assertRoundTrips(schedule)
    }

    @Test
    fun seedIsPreservedEvenWhenScheduleIsOtherwiseClean() {
        val schedule = FaultSchedule(seed = 123456789L) {}
        assertEquals(123456789L, FaultScheduleCodec.decode(FaultScheduleCodec.encode(schedule)).seed)
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val doc =
            """
            faultschedule v2
            # this is the client->server schedule
            seed 5

            fault index 3 drop   # drop the 3rd datagram

            reset 2
            """.trimIndent()
        val schedule = FaultScheduleCodec.decode(doc)
        assertEquals(5L, schedule.seed)
        assertEquals(listOf(ScheduledFault(UnitSelector.Index(3), Fault.Drop)), schedule.faults)
        assertEquals(Termination.ResetAfterUnits(2), schedule.termination)
    }

    @Test
    fun missingHeader_fails() {
        assertFailsWith<IllegalArgumentException> { FaultScheduleCodec.decode("seed 1\nfault all drop\n") }
    }

    @Test
    fun unknownDirective_fails() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                FaultScheduleCodec.decode("faultschedule v2\nseed 1\nbogus 3\n")
            }
        assertTrue(e.message!!.contains("bogus"), "error should name the bad directive: ${e.message}")
    }

    @Test
    fun unknownSelector_fails() {
        assertFailsWith<IllegalArgumentException> { FaultScheduleCodec.decode("faultschedule v2\nfault wat 1 drop\n") }
    }

    @Test
    fun illegalValue_failsViaVariantInvariant() {
        // corrupt.flipMask must be a byte (0..0xFF) — decode re-runs the init require and rejects 999.
        assertFailsWith<IllegalArgumentException> {
            FaultScheduleCodec.decode("faultschedule v2\nfault index 0 corrupt 0 999\n")
        }
    }

    @Test
    fun trailingTokens_fail() {
        assertFailsWith<IllegalArgumentException> { FaultScheduleCodec.decode("faultschedule v2\nfault all drop extra\n") }
    }
}
