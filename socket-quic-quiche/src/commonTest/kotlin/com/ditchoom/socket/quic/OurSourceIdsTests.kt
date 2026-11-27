package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals

class OurSourceIdsTests {
    /** Our ids 1–4 issued below a peer limit of 5, as every client connection starts. */
    private fun fiveHeld(): OurSourceIds = OurSourceIds().apply { for (seq in 1L..4L) issued(seq, seq, 5L) }

    @Test
    fun anIdAtOrAboveRetirePriorToWasRetiredUnasked() {
        val ids = fiveHeld()
        ids.issued(5L, 5L, 5L) // at the limit: Retire Prior To 1, asking for id 0
        assertEquals(PeerRetirement.Unasked, ids.peerRetired(3L, 3L), "mvfst's retirement of id 3 was taken as the ask")
    }

    @Test
    fun theIdRetirePriorToCoversWasAsked() {
        val ids = fiveHeld()
        ids.issued(5L, 5L, 5L)
        assertEquals(PeerRetirement.Asked, ids.peerRetiredHandshakeId())
    }

    @Test
    fun belowThePeersLimitNothingIsAsked() {
        val ids = fiveHeld()
        assertEquals(PeerRetirement.Unasked, ids.peerRetiredHandshakeId(), "s2n-quic's retirement of id 0 was taken as asked")
        assertEquals(PeerRetirement.Unasked, ids.peerRetired(1L, 1L))
    }

    @Test
    fun eachIssueAtTheLimitAsksForTheNextLowestId() {
        val ids = fiveHeld()
        ids.issued(5L, 5L, 5L) // asks for 0
        assertEquals(PeerRetirement.Asked, ids.peerRetiredHandshakeId())
        ids.issued(6L, 6L, 5L) // asks for 1
        assertEquals(PeerRetirement.Unasked, ids.peerRetired(2L, 2L))
        assertEquals(PeerRetirement.Asked, ids.peerRetired(1L, 1L))
    }

    @Test
    fun anIdNeverIssuedIsTheHandshakeId() {
        val ids = fiveHeld()
        ids.issued(5L, 5L, 5L)
        assertEquals(PeerRetirement.Asked, ids.peerRetired(99L, 99L), "an id not issued here is sequence 0, which was asked for")
    }
}
