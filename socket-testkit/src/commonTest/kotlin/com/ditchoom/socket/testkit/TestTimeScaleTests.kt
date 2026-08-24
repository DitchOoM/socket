package com.ditchoom.socket.testkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The clamp in [parseTimeScale] is the one piece of the time-scale mechanism that can be wrong, and
 * until now nothing anywhere asserted it — the suites that depend on `QUIC_TEST_TIME_SCALE` only ever
 * exercised it through a real environment variable, which no test sets.
 *
 * What the clamp has to guarantee is one-directional: a lane can ask for **more** wall-clock and never
 * for less. A value below 1.0 would shorten every deadline in a suite at once — which is how a scale
 * knob turns into a flake generator rather than a fix for one — and an absurd value would hang CI for
 * hours on a typo.
 */
class TestTimeScaleTests {
    @Test
    fun absentOrUnparseableLeavesTheSuiteUnscaled() {
        assertEquals(1.0, parseTimeScale(null), "no env var set is the ordinary local run")
        assertEquals(1.0, parseTimeScale(""), "an exported-but-empty variable is not a request")
        assertEquals(1.0, parseTimeScale("   "), "…nor is whitespace")
        assertEquals(1.0, parseTimeScale("fast"), "a typo must not crash a lane that only wanted time")
    }

    @Test
    fun aScaleIsReadAndSurroundingWhitespaceIsNot() {
        assertEquals(3.0, parseTimeScale("3"))
        assertEquals(2.5, parseTimeScale("2.5"))
        assertEquals(3.0, parseTimeScale(" 3 "), "a shell that quoted the value still means 3")
    }

    @Test
    fun theScaleOnlyEverGrantsTime() {
        assertEquals(1.0, parseTimeScale("0.5"), "below 1.0 would SHORTEN every deadline at once")
        assertEquals(1.0, parseTimeScale("0"))
        assertEquals(1.0, parseTimeScale("-4"))
    }

    @Test
    fun anAbsurdScaleIsCappedSoATypoCannotHangCi() {
        assertEquals(10.0, parseTimeScale("100"))
        assertEquals(10.0, parseTimeScale("1000000"))
    }

    /**
     * Applying the factor uniformly is what preserves a suite's timing *relationships*: every deadline
     * grows by the same multiple, so "the idle timer fires before the read backstop" survives scaling.
     */
    @Test
    fun scalingIsUniformSoRatiosSurvive() {
        val scale = testTimeScale()
        assertEquals(5.seconds * scale, 5.seconds.scaled)
        assertEquals(15.seconds * scale, 15.seconds.scaled)
        assertEquals(3.0, (15.seconds.scaled / 5.seconds.scaled), "the 3:1 ratio must be untouched by any scale")
    }
}
