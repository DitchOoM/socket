package com.ditchoom.socket.http3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The arithmetic rules that make #353's bug unrepresentable.
 *
 * Most of this type's value is enforced by the compiler and so cannot be tested — there is no way to
 * write "assert that assigning a delta to an absolute does not compile". What *is* testable is the
 * narrow set of runtime rules the compiler cannot express: that subtraction is the only way to reach
 * a delta, that it refuses to run backwards, and that the two kinds round-trip.
 */
class QpackInsertCountTests {
    @Test
    fun subtractingTwoAbsolutesYieldsTheDeltaBetweenThem() {
        val known = InsertCount(3)
        val current = InsertCount(10)

        assertEquals(InsertCountDelta(7), current - known)
    }

    @Test
    fun addingThatDeltaBackReachesTheOriginalCount() {
        val known = InsertCount(3)
        val current = InsertCount(10)

        // This is the encoder's side of the exchange: it holds `known`, receives the delta, and must
        // land exactly on what the decoder had. #353 broke precisely this round-trip.
        assertEquals(current, known + (current - known))
    }

    @Test
    fun anInsertCountRefusesToRunBackwards() {
        // A negative increment cannot be encoded and would be a protocol violation if it could be, so
        // subtracting in the wrong order is a local bug worth failing on rather than an increment the
        // peer has to reject for us.
        val failure = assertFailsWith<IllegalArgumentException> { InsertCount(3) - InsertCount(10) }

        assertTrue(
            failure.message?.contains("forward only") == true,
            "the failure should say what the rule is; got: ${failure.message}",
        )
    }

    @Test
    fun equalCountsYieldAZeroDeltaRatherThanFailing() {
        // The decoder computes this every time a Section Acknowledgment has already carried the count
        // as far as the pending increment would: legal, and the caller suppresses the emission.
        assertEquals(InsertCountDelta.ZERO, InsertCount(5) - InsertCount(5))
    }

    @Test
    fun neitherKindAcceptsANegativeValue() {
        assertFailsWith<IllegalArgumentException> { InsertCount(-1) }
        assertFailsWith<IllegalArgumentException> { InsertCountDelta(-1) }
    }

    @Test
    fun countsAndDeltasOrderIndependently() {
        assertTrue(InsertCount(2) < InsertCount(3))
        assertTrue(InsertCountDelta(2) < InsertCountDelta(3))
        assertTrue(InsertCount.ZERO < InsertCount(1))
        assertTrue(InsertCountDelta.ZERO < InsertCountDelta(1))
    }
}
