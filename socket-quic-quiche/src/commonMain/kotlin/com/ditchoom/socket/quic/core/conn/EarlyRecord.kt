package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicEarlyDataChange
import com.ditchoom.socket.quic.QuicEarlyDataRejection
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.EarlyCopy
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_INVALID_STREAM_STATE
import com.ditchoom.socket.quic.core.stream.ReplayStart
import com.ditchoom.socket.quic.core.stream.Replayed
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.Runnable

/** Whether a client's server settled its 0-RTT, as TLS reports it. */
internal sealed interface EarlyFate {
    data object Unsettled : EarlyFate

    data object Accepted : EarlyFate

    class NotAccepted(
        val reason: QuicEarlyDataRejection,
    ) : EarlyFate
}

/** Whether a client keeps its 0-RTT bytes for a replay. */
internal sealed interface EarlyPlan {
    /** No 0-RTT, or a ticket whose parameters cannot be read: quiche keeps what a rejected 0-RTT sent, and re-sends it. */
    data object None : EarlyPlan

    class Keeping(
        val record: EarlyRecord,
    ) : EarlyPlan
}

/** What a client's sends are copied to before its server settles 0-RTT. */
internal fun EarlyPlan.copy(): EarlyCopy =
    when (this) {
        is EarlyPlan.None -> EarlyCopy.Off
        is EarlyPlan.Keeping -> record.copy
    }

/** What settling a client's 0-RTT left: an open connection, or one whose server changed what its early bytes relied on. */
internal sealed interface EarlySettle {
    data object Open : EarlySettle

    class Refused(
        val end: ConnEnd.EarlyDataRejected,
    ) : EarlySettle
}

/**
 * A client offering [resumption]'s ticket with 0-RTT, as [offer] says quiche took it, keeps its early bytes in memory
 * from [factory] and has quiche drop a rejected 0-RTT; any other client keeps nothing. Before the first send.
 */
internal fun earlyPlanOf(
    q: QuicheConnRef,
    offer: Offer,
    resumption: QuicResumption,
    factory: BufferFactory,
): EarlyPlan {
    val held =
        when (offer) {
            is Offer.Offered -> offer.flight == FirstFlight.HeldForEarlyData
            is Offer.NoTicket, is Offer.Unusable -> false
        }
    val ticket =
        when (resumption) {
            is QuicResumption.ResumeWithEarlyData -> resumption.ticket
            is QuicResumption.None, is QuicResumption.Resume -> return EarlyPlan.None
        }
    if (!held) return EarlyPlan.None
    return when (val read = ticketParamsOf(ticket.serialized())) {
        is TicketRead.Unreadable -> EarlyPlan.None
        is TicketRead.Read -> {
            resetStreamsOnEarlyDataReject(q)
            EarlyPlan.Keeping(EarlyRecord(q, factory, ticket.protocol, read.params))
        }
    }
}

/** A client whose server rejects its 0-RTT keeps none of it in quiche, for [EarlyRecord] to replay. Before the first send. */
private fun resetStreamsOnEarlyDataReject(q: QuicheConnRef) = q.api.connResetStreamsOnEarlyDataReject(q.handle)

/** Memory a kept send's bytes are in; a send of no bytes has none. */
private sealed interface KeptBytes {
    data object None : KeptBytes

    class Held(
        val bytes: NativeBytes,
    ) : KeptBytes
}

private class Kept(
    val stream: Long,
    val bytes: KeptBytes,
    val length: Int,
    val end: WriteEnd,
) {
    fun address(): Long =
        when (bytes) {
            is KeptBytes.None -> 0L
            is KeptBytes.Held -> bytes.bytes.address
        }
}

private enum class RecordPhase { Recording, Overflowed, Replaying, Over }

/**
 * A client's 0-RTT bytes: a copy of every send quiche took before its server settled 0-RTT, never the caller's block,
 * so the block runs once. At most the ticket's `initial_max_data` is held, the most quiche lets 0-RTT carry. Freed when
 * the server accepts 0-RTT, when quiche kept a rejected one's streams, once a replay is all in quiche, or at the end.
 */
@Confined
internal class EarlyRecord(
    private val q: QuicheConnRef,
    private val factory: BufferFactory,
    private val protocol: String,
    private val remembered: TicketParams,
) {
    private val kept = ArrayList<Kept>()
    private var bytes = 0L
    private var phase = RecordPhase.Recording
    private val freed = Runnable { free() }

    val copy: EarlyCopy = EarlyCopy.To(::keep)

    private fun keep(
        stream: Long,
        address: Long,
        length: Int,
        end: WriteEnd,
    ) {
        if (phase != RecordPhase.Recording) return
        if (bytes + length > remembered.initialMaxData) {
            phase = RecordPhase.Overflowed
            bytes += length
            return
        }
        if (length == 0) {
            kept += Kept(stream, KeptBytes.None, 0, end)
            return
        }
        when (val memory = NativeBytes.of(factory.allocate(length))) {
            is Addressed.Native -> {
                NativeBytes.copy(address, memory.bytes.address, length)
                kept += Kept(stream, KeptBytes.Held(memory.bytes), length, end)
                bytes += length
            }
            is Addressed.NotAddressable -> phase = RecordPhase.Overflowed
        }
    }

    /** After each datagram quiche read: once the server settled 0-RTT, the bytes are freed, replayed, or refused. */
    fun settle(
        session: ClientSession,
        streams: StreamTable,
        handshake: Handshake,
    ): EarlySettle {
        when (phase) {
            RecordPhase.Recording, RecordPhase.Overflowed -> Unit
            RecordPhase.Replaying, RecordPhase.Over -> return EarlySettle.Open
        }
        val reason =
            when (val fate = session.earlyFate()) {
                is EarlyFate.Unsettled -> return EarlySettle.Open
                is EarlyFate.Accepted -> return settled(streams)
                is EarlyFate.NotAccepted -> fate.reason
            }
        // quiche dropped the streams only if this client was in 0-RTT; one that never offered it sends them in 1-RTT.
        if (kept.isEmpty() || streams.io.capacity(QuicStreamId(kept[0].stream)) != QUICHE_ERR_INVALID_STREAM_STATE.toLong()) {
            return settled(streams)
        }
        val granted = TicketParams.granted(q.api.connPeerTransportParams(q.handle), remembered)
        val verdict =
            when (phase) {
                RecordPhase.Overflowed ->
                    ReplayVerdict.Refuse(QuicEarlyDataChange.ConnectionData(remembered.initialMaxData, granted.initialMaxData, bytes))
                RecordPhase.Recording, RecordPhase.Replaying, RecordPhase.Over ->
                    judge(REPLAY_CONDITIONS, EarlyFacts(protocol, remembered, handshake.negotiated(), granted, useOf(kept)))
            }
        return when (verdict) {
            is ReplayVerdict.Replay -> replay(streams, session.replayStart())
            is ReplayVerdict.Refuse -> {
                settled(streams)
                EarlySettle.Refused(ConnEnd.EarlyDataRejected(reason, verdict.change))
            }
        }
    }

    /** At the end: nothing more is replayed, and the memory goes. */
    fun ended(streams: StreamTable) {
        when (val now = streams.state) {
            is Streams.Open -> now.send.dropReplay()
            is Streams.Closing, is Streams.Ended -> Unit
        }
        free()
    }

    /** The server passed the client's check: a replay held for it goes. */
    fun checked(streams: StreamTable) {
        when (val now = streams.state) {
            is Streams.Open -> if (phase == RecordPhase.Replaying) now.send.releaseReplay()
            is Streams.Closing, is Streams.Ended -> Unit
        }
    }

    private fun replay(
        streams: StreamTable,
        start: ReplayStart,
    ): EarlySettle {
        when (val now = streams.state) {
            is Streams.Open -> {
                now.send.stopCopying()
                phase = RecordPhase.Replaying
                now.send.replay(kept.map { Replayed(it.stream, it.address(), it.length, it.end) }, freed, start)
            }
            is Streams.Closing, is Streams.Ended -> free()
        }
        return EarlySettle.Open
    }

    private fun settled(streams: StreamTable): EarlySettle {
        when (val now = streams.state) {
            is Streams.Open -> now.send.stopCopying()
            is Streams.Closing, is Streams.Ended -> Unit
        }
        free()
        return EarlySettle.Open
    }

    private fun free() {
        for (piece in kept) {
            when (val memory = piece.bytes) {
                is KeptBytes.None -> Unit
                is KeptBytes.Held -> memory.bytes.free()
            }
        }
        kept.clear()
        phase = RecordPhase.Over
    }
}

/** What the kept sends used, by stream: client-initiated ids, bit 1 set for unidirectional (RFC 9000 §2.1). */
private fun useOf(kept: List<Kept>): EarlyUse {
    val perStream = LinkedHashMap<Long, Long>()
    for (piece in kept) perStream[piece.stream] = (perStream[piece.stream] ?: 0L) + piece.length
    var bidi = 0L
    var uni = 0L
    var fullestBidi = StreamBytes(0L, 0L)
    var fullestUni = StreamBytes(0L, 0L)
    for ((stream, carried) in perStream) {
        val count = (stream shr STREAM_TYPE_BITS) + 1
        if (stream and UNI_BIT == 0L) {
            bidi = maxOf(bidi, count)
            if (carried > fullestBidi.bytes) fullestBidi = StreamBytes(stream, carried)
        } else {
            uni = maxOf(uni, count)
            if (carried > fullestUni.bytes) fullestUni = StreamBytes(stream, carried)
        }
    }
    return EarlyUse(bidi, uni, kept.sumOf { it.length.toLong() }, fullestBidi, fullestUni)
}

private const val STREAM_TYPE_BITS = 2
private const val UNI_BIT = 2L
