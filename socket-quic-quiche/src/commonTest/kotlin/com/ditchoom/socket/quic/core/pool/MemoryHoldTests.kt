package com.ditchoom.socket.quic.core.pool

import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * [MemoryHold]: each holder holds through a claim of its own that lets go once, so a holder releasing twice can never
 * free the memory while another still holds it.
 */
class MemoryHoldTests {
    @Test
    fun aClaimReleasedTwiceLetsGoOnceAndTheMemoryStaysHeldByTheOthers() {
        var freed = 0
        val hold = MemoryHold(Runnable { freed++ })
        val connection = assertIs<Hold.Held>(hold.acquire()).claim
        assertSame(ClaimRelease.Released, connection.release())
        assertSame(ClaimRelease.AlreadyReleased, connection.release(), "a second release of the same claim does nothing")
        assertEquals(0, freed, "the founding claim still holds the memory")
        hold.founding.release()
        assertEquals(1, freed)
    }

    @Test
    fun theFoundingClaimReleasedTwiceFreesOnceAndNeverWhileAConnectionHolds() {
        var freed = 0
        val hold = MemoryHold(Runnable { freed++ })
        val connection = assertIs<Hold.Held>(hold.acquire()).claim
        hold.founding.release()
        hold.founding.release()
        assertEquals(0, freed, "the connection still holds the memory")
        connection.release()
        assertEquals(1, freed)
        assertSame(Hold.AlreadyFreed, hold.acquire(), "freed memory is never held again")
    }
}
