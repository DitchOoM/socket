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

    /** Connection credit for every early byte (RFC 9000 §7.4.1, `initial_max_data`). */
    data object ConnectionData : ReplayCondition

    /** Credit on a new stream for every early byte it carried (`initial_max_stream_data_bidi_remote`, `_uni`). */
    data object StreamData : ReplayCondition
}

/**
 * The conditions a replay is judged by. A server that lowered flow-control credit only paces the replay, which waits for
 * MAX_DATA and MAX_STREAM_DATA as any write does, so [ReplayCondition.ConnectionData] and [ReplayCondition.StreamData]
 * are not among them.
 */
internal val REPLAY_CONDITIONS: List<ReplayCondition> =
    listOf(
        ReplayCondition.Protocol,
        ReplayCondition.BidirectionalStreams,
        ReplayCondition.UnidirectionalStreams,
        ReplayCondition.DatagramFrameSize,
    )

/** What the early bytes used: the streams of each kind, as quiche counts them (highest sequence + 1), and the bytes. */
internal class EarlyUse(
    val bidiStreams: Long,
    val uniStreams: Long,
    val bytes: Long,
    /** The stream that carried the most bytes for its kind's credit, and how many. */
    val fullestBidi: StreamBytes,
    val fullestUni: StreamBytes,
)

internal class StreamBytes(
    val stream: Long,
    val bytes: Long,
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

private fun EarlyFacts.judged(condition: ReplayCondition): ReplayVerdict {
    val r = remembered
    val g = granted
    return when (condition) {
        is ReplayCondition.Protocol -> refuseIf(negotiated != protocol) { QuicEarlyDataChange.ApplicationProtocol(protocol, negotiated) }
        is ReplayCondition.BidirectionalStreams ->
            refuseIf(
                g.streamsBidi < use.bidiStreams,
            ) { QuicEarlyDataChange.BidirectionalStreams(r.streamsBidi, g.streamsBidi, use.bidiStreams) }
        is ReplayCondition.UnidirectionalStreams ->
            refuseIf(
                g.streamsUni < use.uniStreams,
            ) { QuicEarlyDataChange.UnidirectionalStreams(r.streamsUni, g.streamsUni, use.uniStreams) }
        is ReplayCondition.DatagramFrameSize ->
            refuseIf(g.datagramFrameSize < r.datagramFrameSize) {
                QuicEarlyDataChange.DatagramFrameSize(r.datagramFrameSize, g.datagramFrameSize)
            }
        is ReplayCondition.ConnectionData ->
            refuseIf(g.initialMaxData < use.bytes) { QuicEarlyDataChange.ConnectionData(r.initialMaxData, g.initialMaxData, use.bytes) }
        is ReplayCondition.StreamData -> streamData()
    }
}

private fun EarlyFacts.streamData(): ReplayVerdict {
    val bidi = use.fullestBidi
    val uni = use.fullestUni
    return when {
        granted.streamDataBidiRemote < bidi.bytes ->
            ReplayVerdict.Refuse(
                QuicEarlyDataChange.StreamData(bidi.stream, remembered.streamDataBidiRemote, granted.streamDataBidiRemote, bidi.bytes),
            )
        granted.streamDataUni < uni.bytes ->
            ReplayVerdict.Refuse(QuicEarlyDataChange.StreamData(uni.stream, remembered.streamDataUni, granted.streamDataUni, uni.bytes))
        else -> ReplayVerdict.Replay
    }
}

private inline fun refuseIf(
    changed: Boolean,
    change: () -> QuicEarlyDataChange,
): ReplayVerdict = if (changed) ReplayVerdict.Refuse(change()) else ReplayVerdict.Replay
