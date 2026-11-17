package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.Wanted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiredDueTests {
    private val record = FiredDue()

    @Test
    fun theArmingHeldWhenItsExpiryRunsIsExpired() {
        assertTrue(record.fell(10))
        assertEquals(Expiry.Due, record.take(Wanted.at(10)))
    }

    @Test
    fun anExpiryOfAnArmingLetGoAsItFiredFindsTheSlotsNextHolderStale() {
        // The operation settled as its deadline fired; the slot went to the next operation, armed for 20.
        assertTrue(record.fell(10))
        assertEquals(Expiry.Stale, record.take(Wanted.at(20)))
        assertEquals(Expiry.Stale, record.take(Wanted.None))
    }

    @Test
    fun aSecondFireWhileTheExpiryIsQueuedIsFoldedIntoItAndTheLatestArmingCounts() {
        assertTrue(record.fell(10))
        // The slot was let go and lent again before the queued expiry ran; the new arming fell due too.
        assertFalse(record.fell(20), "the expiry is queued once")
        assertEquals(Expiry.Due, record.take(Wanted.at(20)))
        assertTrue(record.fell(30), "taken, the expiry can be queued again")
    }

    @Test
    fun aDroppedExpiryCanBeQueuedAgain() {
        assertTrue(record.fell(10))
        record.dropped()
        assertTrue(record.fell(20))
    }
}
