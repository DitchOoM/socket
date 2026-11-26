package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicEarlyDataChange

/**
 * What a replay of a rejected 0-RTT's bytes relies on the server keeping as its ticket remembered: changed, the bytes
 * would be read under assumptions they were not written for, so they are not replayed (RFC 9001 §4.6.2: when 0-RTT is
 * rejected, everything the client assumed might be incorrect).
 */
internal sealed interface ReplayCondition {
    /** The application protocol the bytes were written for (RFC 9001 §4.6.2, §4.6.3). */
    data object Protocol : ReplayCondition

    /** Bidirectional stream credit for every stream the bytes went on (RFC 9000 §7.4.1, `initial_max_streams_bidi`). */
    data object BidirectionalStreams : ReplayCondition

    /** Unidirectional stream credit for every stream the bytes went on (RFC 9000 §7.4.1, `initial_max_streams_uni`). */
    data object UnidirectionalStreams : ReplayCondition

    /** DATAGRAM frames as large as the ticket allowed (RFC 9221 §3, `max_datagram_frame_size`). */
    data object DatagramFrameSize : ReplayCondition
}

/** The conditions a replay is judged by: the owner's list. Flow-control credit is not one; it only paces a replay. */
internal val REPLAY_CONDITIONS: List<ReplayCondition> =
    listOf(
        ReplayCondition.Protocol,
        ReplayCondition.BidirectionalStreams,
        ReplayCondition.UnidirectionalStreams,
        ReplayCondition.DatagramFrameSize,
    )

/** The streams of each kind the early bytes went on, as quiche counts them: the highest sequence + 1. */
internal class EarlyUse(
    val bidiStreams: Long,
    val uniStreams: Long,
)

/** Everything a replay is judged on. */
internal class EarlyFacts(
    val protocol: String,
    val remembered: TicketParams,
    val negotiated: String,
    val granted: TicketParams,
    val use: EarlyUse,
)

/** Whether a rejected 0-RTT's bytes are replayed. */
internal sealed interface ReplayVerdict {
    data object Replay : ReplayVerdict

    class Refuse(
        val change: QuicEarlyDataChange,
    ) : ReplayVerdict
}

/** The first of [conditions] the server changed, or [ReplayVerdict.Replay] when it kept them all. */
internal fun judge(
    conditions: List<ReplayCondition>,
    facts: EarlyFacts,
): ReplayVerdict {
    for (condition in conditions) {
        when (val verdict = facts.judged(condition)) {
            is ReplayVerdict.Replay -> Unit
            is ReplayVerdict.Refuse -> return verdict
        }
    }
    return ReplayVerdict.Replay
}

private fun EarlyFacts.judged(condition: ReplayCondition): ReplayVerdict =
    when (condition) {
        is ReplayCondition.Protocol ->
            when (negotiated) {
                protocol -> ReplayVerdict.Replay
                else -> ReplayVerdict.Refuse(QuicEarlyDataChange.ApplicationProtocol(protocol, negotiated))
            }
        is ReplayCondition.BidirectionalStreams ->
            when {
                granted.streamsBidi < use.bidiStreams ->
                    ReplayVerdict.Refuse(
                        QuicEarlyDataChange.BidirectionalStreams(remembered.streamsBidi, granted.streamsBidi, use.bidiStreams),
                    )
                else -> ReplayVerdict.Replay
            }
        is ReplayCondition.UnidirectionalStreams ->
            when {
                granted.streamsUni < use.uniStreams ->
                    ReplayVerdict.Refuse(
                        QuicEarlyDataChange.UnidirectionalStreams(remembered.streamsUni, granted.streamsUni, use.uniStreams),
                    )
                else -> ReplayVerdict.Replay
            }
        is ReplayCondition.DatagramFrameSize ->
            when {
                granted.datagramFrameSize < remembered.datagramFrameSize ->
                    ReplayVerdict.Refuse(QuicEarlyDataChange.DatagramFrameSize(remembered.datagramFrameSize, granted.datagramFrameSize))
                else -> ReplayVerdict.Replay
            }
    }
