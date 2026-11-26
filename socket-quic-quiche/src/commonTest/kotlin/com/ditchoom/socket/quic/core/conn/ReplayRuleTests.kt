package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.QuicEarlyDataChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The one seam a rejected 0-RTT's replay is judged by, condition by condition, and the ticket parameters it reads. */
class ReplayRuleTests {
    private val remembered = TicketParams(1000, 500, 400, 10, 3, 1200)
    private val use = EarlyUse(2, 2, 600, StreamBytes(4, 300), StreamBytes(2, 300))

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
                ReplayCondition.ConnectionData,
                ReplayCondition.StreamData,
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
    fun lessCreditIsRefusedOnlyWhereItsConditionIsJudged() {
        val low = params(maxData = 100, bidiRemote = 100)
        assertEquals(ReplayVerdict.Replay, judge(REPLAY_CONDITIONS, facts(low)), "flow control only paces a replay")
        val connection = assertIs<ReplayVerdict.Refuse>(judge(only(ReplayCondition.ConnectionData), facts(low)))
        assertEquals(QuicEarlyDataChange.ConnectionData(1000, 100, 600), connection.change)
        val stream = assertIs<ReplayVerdict.Refuse>(judge(only(ReplayCondition.StreamData), facts(low)))
        assertEquals(QuicEarlyDataChange.StreamData(4, 500, 100, 300), stream.change)
    }

    @Test
    fun aSessionsParametersAreReadFromQuichesEncoding() {
        val read =
            assertIs<TicketRead.Read>(
                ticketParamsOf(session(byteArrayOf(0x04, 0x02, 0x44, 0x00, 0x09, 0x01, 0x05, 0x20, 0x02, 0x44, 0xB0.toByte()))),
            )
        assertEquals(
            listOf(0x400L, 0L, 0L, 0L, 5L, 0x4B0L),
            read.params.let {
                listOf(it.initialMaxData, it.streamDataBidiRemote, it.streamDataUni, it.streamsBidi, it.streamsUni, it.datagramFrameSize)
            },
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
        maxData: Long = remembered.initialMaxData,
        bidiRemote: Long = remembered.streamDataBidiRemote,
        streamsBidi: Long = remembered.streamsBidi,
        streamsUni: Long = remembered.streamsUni,
        datagram: Long = remembered.datagramFrameSize,
    ) = TicketParams(maxData, bidiRemote, remembered.streamDataUni, streamsBidi, streamsUni, datagram)
}
