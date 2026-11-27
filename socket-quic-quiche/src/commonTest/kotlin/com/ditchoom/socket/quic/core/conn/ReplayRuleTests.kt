package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.QuicEarlyDataChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The one seam a rejected 0-RTT's replay is judged by, condition by condition, and the ticket parameters it reads. */
class ReplayRuleTests {
    private val remembered = TicketParams(10, 3, 1200)
    private val use = EarlyUse(2, 2)

    private fun facts(
        granted: TicketParams = remembered,
        negotiated: String = "h3",
    ) = EarlyFacts("h3", remembered, negotiated, granted, use)

    private fun only(condition: ReplayCondition) = listOf(condition)

    @Test
    fun aServerThatKeptEverythingIsReplayedUnderEveryCondition() {
        val all =
            listOf(
                ReplayCondition.Protocol,
                ReplayCondition.BidirectionalStreams,
                ReplayCondition.UnidirectionalStreams,
                ReplayCondition.DatagramFrameSize,
            )
        assertEquals(ReplayVerdict.Replay, judge(all, facts()))
    }

    @Test
    fun anotherProtocolIsRefused() {
        val refused = assertIs<ReplayVerdict.Refuse>(judge(only(ReplayCondition.Protocol), facts(negotiated = "h4")))
        assertEquals(QuicEarlyDataChange.ApplicationProtocol("h3", "h4"), refused.change)
    }

    @Test
    fun fewerStreamsThanTheBytesWentOnAreRefusedAndFewerThatStillFitAreNot() {
        val tooFew = assertIs<ReplayVerdict.Refuse>(judge(only(ReplayCondition.UnidirectionalStreams), facts(params(streamsUni = 1))))
        assertEquals(QuicEarlyDataChange.UnidirectionalStreams(3, 1, 2), tooFew.change)
        assertEquals(ReplayVerdict.Replay, judge(only(ReplayCondition.UnidirectionalStreams), facts(params(streamsUni = 2))))
        val bidi = assertIs<ReplayVerdict.Refuse>(judge(only(ReplayCondition.BidirectionalStreams), facts(params(streamsBidi = 1))))
        assertEquals(QuicEarlyDataChange.BidirectionalStreams(10, 1, 2), bidi.change)
    }

    @Test
    fun smallerDatagramsAreRefused() {
        val refused = assertIs<ReplayVerdict.Refuse>(judge(only(ReplayCondition.DatagramFrameSize), facts(params(datagram = 0))))
        assertEquals(QuicEarlyDataChange.DatagramFrameSize(1200, 0), refused.change)
    }

    @Test
    fun aSessionsParametersAreReadFromQuichesEncoding() {
        val read =
            assertIs<TicketRead.Read>(
                ticketParamsOf(session(byteArrayOf(0x04, 0x02, 0x44, 0x00, 0x09, 0x01, 0x05, 0x20, 0x02, 0x44, 0xB0.toByte()))),
            )
        assertEquals(
            listOf(0L, 5L, 0x4B0L),
            read.params.let {
                listOf(it.streamsBidi, it.streamsUni, it.datagramFrameSize)
            },
            "initial_max_data is skipped",
        )
        assertEquals(TicketRead.Unreadable, ticketParamsOf(session(byteArrayOf(0x04, 0x05, 0x01))), "a value past the end")
    }

    /** quiche's encoding: a TLS session of three bytes, then [params]. */
    private fun session(params: ByteArray) =
        BufferFactory.managed().allocate(16 + 3 + params.size).apply {
            writeLong(3L)
            writeBytes(byteArrayOf(1, 2, 3))
            writeLong(params.size.toLong())
            writeBytes(params)
            resetForRead()
        }

    private fun params(
        streamsBidi: Long = remembered.streamsBidi,
        streamsUni: Long = remembered.streamsUni,
        datagram: Long = remembered.datagramFrameSize,
    ) = TicketParams(streamsBidi, streamsUni, datagram)
}
