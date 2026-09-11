package com.ditchoom.socket.testkit.echo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every case is a shape the 20 h Android walk of 2026-09-09 produced (#599). That walk recorded 149
 * "failures" on a connection that never lost a byte: each was the probe's own 400 ms read deadline,
 * and one late reply then read as up to eight of them because the next reads inherited the lag.
 */
class EchoLedgerTests {
    private val ledger = EchoLedger()

    /** A ledger whose estimate has settled on the walk's Wi-Fi path: 58 ms round trips. */
    private fun warmedTo58ms(): EchoLedger {
        var t = Duration.ZERO
        repeat(20) { i ->
            ledger.sent(i + 1, "probe-${i + 1};", t)
            ledger.received("probe-${i + 1};", t + 58.milliseconds)
            t += 250.milliseconds
        }
        return ledger
    }

    @Test
    fun aReplyInsideTheDeadlineIsOnTime() {
        val ledger = warmedTo58ms()
        ledger.sent(21, "probe-21;", 10_000.milliseconds)

        val read = ledger.received("probe-21;", 10_060.milliseconds)

        assertEquals(EchoRead.Consumed(listOf(EchoOutcome.OnTime(21, 60.milliseconds)), owedBytes = 0), read)
    }

    /** `ECHO-FAIL seq=N after=405ms err=TimeoutCancellationException` on a stream that lost nothing. */
    @Test
    fun aReplyAfterTheDeadlineIsLateAndNotAFailure() {
        val ledger = warmedTo58ms()
        val deadline = ledger.readDeadline
        ledger.sent(21, "probe-21;", 10_000.milliseconds)

        val read = assertIs<EchoRead.Consumed>(ledger.received("probe-21;", 10_405.milliseconds))

        val late = assertIs<EchoOutcome.Late>(read.outcomes.single())
        assertEquals(405.milliseconds, late.roundTrip)
        assertEquals(deadline, late.deadline)
        assertTrue(late.line(0).startsWith("ECHO-LATE seq=21 rtt=405ms late=+"), late.line(0))
    }

    /** Eight sends outstanding, one read carrying all eight replies: eight outcomes, each judged on its own. */
    @Test
    fun oneLateReplyIsOneLateEchoNotEightFailures() {
        val ledger = warmedTo58ms()
        val deadline = ledger.readDeadline
        var t = 10_000.milliseconds
        for (seq in 21..28) {
            ledger.sent(seq, "probe-$seq;", t)
            t += 250.milliseconds
        }

        val read = assertIs<EchoRead.Consumed>(ledger.received((21..28).joinToString("") { "probe-$it;" }, t))

        assertEquals((21..28).toList(), read.outcomes.map { it.seq })
        assertEquals(0, read.owedBytes)
        assertEquals((8 downTo 1).map { (it * 250).milliseconds }, read.outcomes.map { it.roundTrip }, "each from its own send")
        val late = read.outcomes.filterIsInstance<EchoOutcome.Late>()
        assertEquals(read.outcomes.count { it.roundTrip > deadline }, late.size, "late is judged per exchange")
        assertEquals((21..(20 + late.size)).toList(), late.map { it.seq }, "and by age, oldest first")
    }

    /** The one-behind case that read `rtt=5ms`: a coalesced reply measures from each payload's own send. */
    @Test
    fun aCoalescedReadGivesEachEchoItsOwnRoundTrip() {
        ledger.sent(1, "probe-1;", 0.milliseconds)
        ledger.sent(2, "probe-2;", 250.milliseconds)
        ledger.sent(3, "probe-3;", 500.milliseconds)

        val read = assertIs<EchoRead.Consumed>(ledger.received("probe-1;probe-2;probe-3;", 700.milliseconds))

        assertEquals(listOf(700.milliseconds, 450.milliseconds, 200.milliseconds), read.outcomes.map { it.roundTrip })
    }

    @Test
    fun theDeadlineFollowsTheMeasuredPathNotAConstant() {
        assertEquals(RttEstimate.Unsampled.probeTimeout, ledger.readDeadline, "before a sample, the RFC's initial estimate")
        assertEquals(1024.milliseconds, RttEstimate.Unsampled.probeTimeout)

        val wifi = warmedTo58ms().readDeadline
        assertTrue(wifi > 58.milliseconds && wifi < 400.milliseconds, "a 58 ms path is judged by a 58 ms path's timeout, not 400 ms: $wifi")

        val cellular = EchoLedger()
        var t = Duration.ZERO
        repeat(20) { i ->
            cellular.sent(i + 1, "probe-${i + 1};", t)
            cellular.received("probe-${i + 1};", t + 200.milliseconds)
            t += 250.milliseconds
        }
        assertTrue(cellular.readDeadline > wifi, "a slower path earns a longer deadline: cellular=${cellular.readDeadline} wifi=$wifi")
    }

    @Test
    fun aPartialEchoStaysOwedUntilTheRestArrives() {
        ledger.sent(1, "probe-1;", 0.milliseconds)
        ledger.sent(2, "probe-2;", 250.milliseconds)

        val first = assertIs<EchoRead.Consumed>(ledger.received("pro", 300.milliseconds))
        assertEquals(emptyList(), first.outcomes)
        assertEquals("be-1;probe-2;".length, first.owedBytes)

        val second = assertIs<EchoRead.Consumed>(ledger.received("be-1;probe-2;", 320.milliseconds))
        assertEquals(listOf(1, 2), second.outcomes.map { it.seq })
        assertEquals(320.milliseconds, second.outcomes.first().roundTrip, "measured from the send, not from the first fragment")
        assertEquals(0, second.owedBytes)
    }

    @Test
    fun bytesThatAreNotThePrefixOfWhatIsOwedAreADivergence() {
        ledger.sent(1, "probe-1;", 0.milliseconds)

        val read = ledger.received("probe-9;", 60.milliseconds)

        assertEquals(EchoRead.Diverged(atByte = 6, expected = "probe-1;", received = "probe-9;"), read)
        assertEquals("probe-1;".length, ledger.owedBytes, "a divergence consumes nothing")
    }

    @Test
    fun anOverdueEchoIsAnnouncedOnceAndOnlyPastItsOwnDeadline() {
        val ledger = warmedTo58ms()
        val deadline = ledger.readDeadline
        ledger.sent(21, "probe-21;", 10_000.milliseconds)
        ledger.sent(22, "probe-22;", 10_250.milliseconds)

        assertEquals(emptyList(), ledger.overdue(10_000.milliseconds + deadline), "at the deadline it is not yet overdue")
        val crossed = ledger.overdue(10_001.milliseconds + deadline)
        assertEquals(listOf(21), crossed.map { it.seq }, "seq 22 is younger than its deadline")
        assertEquals(deadline, crossed.single().deadline)
        assertEquals(emptyList(), ledger.overdue(10_002.milliseconds + deadline), "seq 21 is not announced twice")
        assertEquals(listOf(22), ledger.overdue(10_251.milliseconds + deadline).map { it.seq })
    }

    @Test
    fun whatIsStillOwedWhenTheConnectionEndsIsWhatFailed() {
        ledger.sent(1, "probe-1;", 0.milliseconds)
        ledger.sent(2, "probe-2;", 250.milliseconds)
        ledger.received("probe-1;", 300.milliseconds)
        ledger.sent(3, "probe-3;", 500.milliseconds)

        assertEquals(EchoUnanswered.Some(count = 2, firstSeq = 2, lastSeq = 3), ledger.abandon())
        assertEquals(EchoUnanswered.None, ledger.abandon())
        assertEquals(0, ledger.owedBytes)
    }

    @Test
    fun theEstimateFollowsRfc9002() {
        val first = RttEstimate.Unsampled.with(100.milliseconds)
        assertEquals(RttEstimate.Sampled(smoothed = 100.milliseconds, variance = 50.milliseconds), first)

        val second = first.with(200.milliseconds)
        assertEquals(112.5.milliseconds, second.smoothed, "7/8 · 100 + 1/8 · 200")
        assertEquals(62.5.milliseconds, second.variance, "3/4 · 50 + 1/4 · |100 − 200|")
        assertEquals(112.5.milliseconds + 250.milliseconds + 25.milliseconds, second.probeTimeout)
    }
}
