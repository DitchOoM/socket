package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.initial
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.peer
import com.ditchoom.socket.quic.core.demux.ServerRig.Companion.short
import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Demux] routing over a scripted quiche and a fake socket: no socket, no thread, no handshake. */
class DemuxTests {
    /** quiche that answers each datagram a connection reads with one datagram of its own. */
    private class AnsweringApi(
        private val real: StubQuicheApi = StubQuicheApi(),
    ) : QuicheApi by real {
        val arrivals: List<QuicheRecvInfo> get() = real.recvArrivals
        private var owed = 0

        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            recvInfo: QuicheRecvInfo,
        ): Int {
            owed++
            return real.connRecv(conn, buf, bufLen, recvInfo)
        }

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            if (owed == 0) return 0
            owed--
            return REPLY
        }

        override fun negotiateVersion(
            scidAddr: Long,
            scidLen: Int,
            dcidAddr: Long,
            dcidLen: Int,
            outAddr: Long,
            outLen: Int,
        ): Int = VN_LENGTH
    }

    @Test
    fun eachDatagramGoesToTheConnectionItsIdNamesAndEachReplyToThatConnectionsSender() {
        val api = AnsweringApi()
        val rig = ServerRig(api)
        rig.io.arrive(initial(Random(1).nextBytes(8)), peer(1))
        rig.io.arrive(initial(Random(2).nextBytes(8)), peer(2))
        rig.pass(0)
        assertEquals(2L, rig.counts.accepted)
        assertEquals(listOf(peer(1), peer(2)), rig.accepted.map { it.peer })
        rig.io.sent.clear()

        // One datagram per pass, so each pass has one connection to answer.
        val first = rig.accepted[0]
        val second = rig.accepted[1]
        rig.io.arrive(short(second.scid0, second.scid1), peer(2))
        rig.pass(1)
        rig.io.arrive(short(first.scid0, first.scid1), peer(1))
        rig.pass(2)

        assertEquals(2L, rig.counts.routed, "the two short headers were routed; an Initial that starts a connection is its first datagram")
        val arrivals = api.arrivals
        assertEquals(arrivals[1], arrivals[2], "the short header naming the second id reached the second connection")
        assertEquals(arrivals[0], arrivals[3], "the short header naming the first id reached the first connection")
        assertEquals(
            listOf(peer(2), peer(1)),
            rig.io.sent.map { (it.target as SendTarget.ServerReply).to },
            "each reply goes to the sender of the datagram its connection read",
        )
    }

    @Test
    fun aRetransmittedInitialGoesToTheConnectionItStartedAndStartsNoOther() {
        val rig = ServerRig()
        val dcid = Random(3).nextBytes(8)
        rig.io.arrive(initial(dcid), peer(1))
        rig.pass(0)
        rig.io.arrive(initial(dcid), peer(1))
        rig.pass(1)
        assertEquals(1L, rig.counts.accepted)
        assertEquals(1L, rig.counts.routed, "the retransmission was routed")
    }

    @Test
    fun aShortHeaderNamingNoConnectionIsDropped() {
        val rig = ServerRig()
        rig.io.arrive(short(1L, 2L), peer(1))
        rig.pass(0)
        assertEquals(1L, rig.counts.droppedUnknown)
        assertEquals(0L, rig.counts.accepted)
    }

    @Test
    fun anUnsupportedVersionIsAnsweredToItsSenderAndARuntIsNot() {
        val rig = ServerRig(AnsweringApi())
        rig.io.arrive(initial(Random(4).nextBytes(8), version = 0x1a2a3a4a), peer(5))
        rig.io.arrive(initial(Random(5).nextBytes(8), version = 0x1a2a3a4a, size = 600), peer(6))
        rig.pass(0)
        assertEquals(1L, rig.counts.versionNegotiationsSent)
        assertEquals(1L, rig.counts.droppedRunt)
        assertEquals(listOf(peer(5)), rig.io.sent.map { (it.target as SendTarget.ServerReply).to })
        assertEquals(listOf(VN_LENGTH), rig.io.sent.map { it.bytes.size })
    }

    @Test
    fun aRuntInitialStartsNoConnection() {
        val rig = ServerRig()
        rig.io.arrive(initial(Random(6).nextBytes(8), size = 1_100), peer(1))
        rig.pass(0)
        assertEquals(1L, rig.counts.droppedRunt)
        assertEquals(0L, rig.counts.accepted)
    }

    @Test
    fun aClosingServerTellsEveryConnectionToCloseThenEndsThemAndStops() {
        val api = StubQuicheApi()
        val rig = ServerRig(api)
        rig.io.arrive(initial(Random(7).nextBytes(8)), peer(1))
        rig.pass(0)
        rig.demux.beginShutdown()
        rig.pass(1)
        rig.pass(2)
        assertEquals(ServerPhase.Stopped, rig.demux.phase())
        assertEquals<List<QuicError>>(listOf(QuicError.NoError), api.closeErrors, "told to close with NO_ERROR")
        assertEquals<List<ConnEnd>>(listOf(ConnEnd.ServerClosed), rig.sinks.single().ended)
        assertEquals(0, rig.demux.connections)
    }

    private companion object {
        const val REPLY = 100
        const val VN_LENGTH = 60
    }
}
