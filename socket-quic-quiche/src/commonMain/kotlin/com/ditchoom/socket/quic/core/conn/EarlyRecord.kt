package com.ditchoom.socket.quic.core.conn

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
 * from [allocate] and has quiche drop a rejected 0-RTT; any other client keeps nothing. Before the first send.
 */
internal fun earlyPlanOf(
    q: QuicheConnRef,
    offer: Offer,
    resumption: QuicResumption,
    allocate: (Int) -> NativeBytes,
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
            EarlyPlan.Keeping(EarlyRecord(q, allocate, ticket.protocol, read.params))
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

private enum class RecordPhase { Recording, Replaying, Over }

/**
 * A client's 0-RTT bytes: a copy of every send quiche took before its server settled 0-RTT, never the caller's block,
 * so the block runs once. It holds what quiche took, which quiche bounds by the ticket's `initial_max_data`: its
 * connection credit until the server settles 0-RTT (RFC 9000 §7.4.1). Freed when
 * the server accepts 0-RTT, when quiche kept a rejected one's streams, once a replay is all in quiche, or at the end.
 */
@Confined
internal class EarlyRecord(
    private val q: QuicheConnRef,
    /** Memory with an address, from the connection's factory, which answers nothing else. */
    private val allocate: (Int) -> NativeBytes,
    private val protocol: String,
    private val remembered: TicketParams,
) {
    private val kept = ArrayList<Kept>()
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
        if (length == 0) {
            kept += Kept(stream, KeptBytes.None, 0, end)
            return
        }
        val memory = allocate(length)
        NativeBytes.copy(address, memory.address, length)
        kept += Kept(stream, KeptBytes.Held(memory), length, end)
    }

    /** After each datagram quiche read: once the server settled 0-RTT, the bytes are freed, replayed, or refused. */
    fun settle(
        session: ClientSession,
        streams: StreamTable,
        handshake: Handshake,
    ): EarlySettle {
        when (phase) {
            RecordPhase.Recording -> Unit
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
        val verdict = judge(REPLAY_CONDITIONS, EarlyFacts(protocol, remembered, handshake.negotiated(), granted, useOf(kept)))
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

/** The streams of each kind the kept sends went on: client-initiated ids, bit 1 set for unidirectional (RFC 9000 §2.1). */
private fun useOf(kept: List<Kept>): EarlyUse {
    var bidi = 0L
    var uni = 0L
    for (piece in kept) {
        val count = (piece.stream shr STREAM_TYPE_BITS) + 1
        if (piece.stream and UNI_BIT == 0L) bidi = maxOf(bidi, count) else uni = maxOf(uni, count)
    }
    return EarlyUse(bidi, uni)
}

private const val STREAM_TYPE_BITS = 2
private const val UNI_BIT = 2L
