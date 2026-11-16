package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.initial
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.peer
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import org.junit.Rule
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Admission] against a socket whose kernel buffer is bounded: what is started, held and dropped, pass by pass. */
class AdmissionTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private fun burst(
        rig: ServerRig,
        count: Int,
        from: Int = 0,
    ) = repeat(count) { rig.io.arrive(initial(Random(from + it).nextBytes(8)), peer(from + it)) }

    /** quiche whose handshakes never complete, so each connection stays in progress. */
    private fun handshaking() =
        com.ditchoom.socket.quic
            .StubQuicheApi()
            .apply { established = false }

    @Test
    fun aBurstOverThePassBoundIsHeldAndStartedOverTheNextPassesWithNothingDropped() {
        val rig = ServerRig(bounds = AdmissionBounds(perPass = 4, inProgress = 1_000), backlog = 64)
        burst(rig, 10)
        rig.pass(0)
        assertEquals(4L, rig.counts.accepted, "a pass starts at most its bound")
        assertEquals(10L, rig.counts.backlogged, "every Initial waited in the backlog, not in the kernel")
        rig.pass(1)
        rig.pass(2)
        assertEquals(10L, rig.counts.accepted)
        assertEquals(0L, rig.counts.droppedAdmission + rig.counts.droppedBacklog)
    }

    @Test
    fun aPassThatStartedAllItMayOwesTheLoopAnotherPass() {
        val rig = ServerRig(bounds = AdmissionBounds(perPass = 2, inProgress = 1_000))
        burst(rig, 3)
        rig.pass(0)
        assertEquals(2L, rig.counts.accepted)
        assertEquals(1, rig.loop.port.runWork(), "the pass queued work that only makes the loop pass again")
    }

    @Test
    fun theSocketIsReadBetweenChunksSoABoundedKernelBufferNeverOverflows() {
        val rig = ServerRig(bounds = AdmissionBounds(perPass = 64, inProgress = 1_000), backlog = 64, kernelBuffer = 16)
        burst(rig, 16)
        rig.pass(0)
        burst(rig, 16, from = 16)
        rig.pass(1)
        burst(rig, 8, from = 32)
        rig.pass(2)
        assertEquals(0, rig.io.kernelDrops)
        assertEquals(40L, rig.counts.accepted)
    }

    @Test
    fun atTheInProgressBoundHeldInitialsWaitAndNewOnesAreDropped() {
        val rig = ServerRig(handshaking(), bounds = AdmissionBounds(perPass = 64, inProgress = 3))
        burst(rig, 5)
        rig.pass(0)
        assertEquals(3L, rig.counts.accepted)
        assertEquals(3L, rig.admission.handshakesInProgress())
        assertEquals(2, rig.admission.backlog.size, "what was held when the bound was reached waits for a handshake to end")
        burst(rig, 1, from = 5)
        rig.pass(1)
        assertEquals(1L, rig.counts.droppedAdmission, "an Initial that arrives at the bound is dropped; its client retransmits")
    }

    @Test
    fun aFullBacklogDropsTheInitialAndCountsIt() {
        val rig = ServerRig(bounds = AdmissionBounds(perPass = 1, inProgress = 1_000), backlog = 4)
        burst(rig, 6)
        rig.pass(0)
        assertEquals(1L, rig.counts.accepted)
        assertEquals(2L, rig.counts.droppedBacklog, "four held, then two found the backlog full")
    }

    @Test
    fun anEndedHandshakeNoLongerCountsAgainstTheBound() {
        val rig = ServerRig(handshaking(), bounds = AdmissionBounds(perPass = 64, inProgress = 2))
        burst(rig, 2)
        rig.pass(0)
        assertEquals(2L, rig.admission.handshakesInProgress())
        rig.demux.endAll(ConnEnd.ServerClosed)
        assertEquals(0L, rig.admission.handshakesInProgress())
    }
}
