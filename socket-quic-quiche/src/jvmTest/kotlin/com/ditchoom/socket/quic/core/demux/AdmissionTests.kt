package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.initial
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.peer
import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Admission] against a socket whose kernel buffer is bounded: what is started, held and dropped, pass by pass. */
class AdmissionTests {
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

    /**
     * A pass whose receive time runs out while Initials are still held leaves them for the next pass, where the clients'
     * retransmits of the same Initials arrive too: each client is still one connection. On the Android emulators the
     * server accepted 1,500 to 1,800 connections for 1,024 clients once handshakes were cut short by the receive budget.
     */
    @Test
    fun anInitialHeldPastTheReceiveBudgetAndItsRetransmitStartOneConnection() {
        lateinit var rig: ServerRig
        // Each handshake started takes a millisecond of the loop's clock, so a chunk of them spends the pass's budget.
        val slow =
            object : com.ditchoom.socket.quic.QuicheApi by com.ditchoom.socket.quic
                .StubQuicheApi() {
                override fun accept(
                    scidAddr: Long,
                    scidLen: Int,
                    odcidAddr: Long,
                    odcidLen: Int,
                    localAddr: Long,
                    localAddrLen: Int,
                    peerAddr: Long,
                    peerAddrLen: Int,
                    config: com.ditchoom.socket.quic.QuicheConfig,
                ): com.ditchoom.socket.quic.QuicheConn {
                    rig.clock.at =
                        com.ditchoom.socket.quic.core.time
                            .Nanos(rig.clock.at.value + 1_000_000L)
                    return com.ditchoom.socket.quic
                        .QuicheConn(1L)
                }
            }
        rig = ServerRig(slow, bounds = AdmissionBounds(perPass = 256, inProgress = 1_000), backlog = 256)
        val clients = 40
        burst(rig, clients)
        rig.pass(rig.clock.at.value)
        assertEquals(true, rig.counts.accepted < clients, "the first pass's budget ran out with Initials held")
        repeat(4) {
            burst(rig, clients)
            rig.pass(rig.clock.at.value)
        }
        assertEquals(clients.toLong(), rig.counts.accepted, "each client, retransmits and all, is one connection")
    }

    /**
     * Reading the socket is not cut short by the receive budget: on the Android emulators a read of 64 datagrams spent the
     * budget, the server read one or two batches a pass, and what waited in the kernel went stale. Client Initials sent
     * before the server answered were read after their connection had ended, and each started another connection
     * (1,500 to 1,800 accepted for 1,024 clients).
     */
    @Test
    fun aPassReadsItsSocketUpToItsCountEvenWhenEachReadTakesAMillisecond() {
        val rig = ServerRig(bounds = AdmissionBounds(perPass = 256, inProgress = 1_000))
        rig.io.onRead = {
            rig.clock.at =
                com.ditchoom.socket.quic.core.time
                    .Nanos(rig.clock.at.value + 1_000_000L)
        }
        repeat(300) { rig.io.arrive(ServerRig.short(it.toLong(), it.toLong()), peer(it)) }
        rig.pass(rig.clock.at.value)
        assertEquals(0, rig.io.unread, "every datagram waiting was read in the pass")
        assertEquals(300L, rig.counts.droppedUnknown)
    }

    /**
     * An Initial that arrives after its connection ended (a retransmit the client sent before the server answered, read
     * late) starts no new connection: the client's first destination id stays the ended connection's.
     */
    @Test
    fun aStaleInitialOfAConnectionThatEndedStartsNoOther() {
        val rig = ServerRig()
        val dcid = Random(7).nextBytes(8)
        rig.io.arrive(initial(dcid), peer(0))
        rig.pass(0)
        assertEquals(1L, rig.counts.accepted)
        rig.conns.single().endWithLoop(ConnEnd.ServerClosed)
        rig.pass(1)
        assertEquals(0, rig.demux.connections, "the ended connection is gone")

        rig.io.arrive(initial(dcid), peer(0))
        rig.pass(2)
        assertEquals(1L, rig.counts.accepted, "the stale Initial started nothing")
        assertEquals(0, rig.demux.connections)
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
