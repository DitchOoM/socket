@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.EarlyDataAcceptance
import com.ditchoom.socket.quic.HandlerStart
import com.ditchoom.socket.quic.HandshakeRecord
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.conn.ConnSink
import com.ditchoom.socket.quic.core.conn.ConnectReport
import com.ditchoom.socket.quic.core.conn.EarlyReport
import com.ditchoom.socket.quic.core.conn.HandshakeFacts
import com.ditchoom.socket.quic.core.conn.HandshakeFate
import com.ditchoom.socket.quic.core.conn.HeldReads
import com.ditchoom.socket.quic.core.conn.HeldTake
import com.ditchoom.socket.quic.core.conn.Owed
import com.ditchoom.socket.quic.core.conn.free
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.trace.record
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** How a connection stands for its callers: open, or ended and how. */
internal sealed interface EdgeEnd {
    data object Open : EdgeEnd

    /**
     * Ended with [end]; [held] is what it had received that no read returned, [outcome] how a close asked of it went,
     * and [handshake] whether its handshake had completed, with what it settled.
     */
    class Ended(
        val end: ConnEnd,
        val held: HeldReads,
        val outcome: CloseOutcome,
        val handshake: HandshakeFate,
    ) : EdgeEnd
}

/** A stream the peer opened, and whether it arrived before the handshake completed. */
internal class PeerStream(
    val id: Long,
    val arrival: Arrival,
)

/**
 * What callers of one connection see: its public state, the streams the peer opened, a server's accepted 0-RTT, a
 * client's session ticket and the end. The connection's [ConnSink]: told inside the connection, it only publishes, and
 * runs no caller code.
 */
@Published
internal class EdgeState(
    /** Where the connection copies the negotiated ALPN; read here once, at establishment. */
    private val alpn: PlatformBuffer,
    private val capture: TraceCapture,
    /** Where a client's ticket is kept for its next connection to the same server. */
    private val tickets: TicketHome,
    /** Where a client's server certificates are kept for its check. */
    private val chain: ChainHome,
) : ConnSink {
    private val endCell = AtomicReference<EdgeEnd>(EdgeEnd.Open)
    val state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)

    /** The streams the peer opened, oldest first, by kind. */
    val incoming = IncomingQueues()
    val closed = CompletableDeferred<EdgeEnd.Ended>()

    /** A server's: whether it accepted the client's 0-RTT before the handshake completed. */
    val earlyData = MutableStateFlow<EarlyDataAcceptance>(EarlyDataAcceptance.None)

    /** What the handshake settled, kept after [state] moves on to Closed for a block that outlives the close. */
    val handshake = MutableStateFlow<HandshakeRecord>(HandshakeRecord.Pending)

    /** A server's: when the handler of this connection starts. */
    val start = MutableStateFlow<HandlerStart>(HandlerStart.NotYet)

    /** A client's: the session ticket its server issued. */
    val ticket = MutableStateFlow<QuicSessionTicketState>(QuicSessionTicketState.NotIssued)

    /**
     * What the connection still holds for its readers once it closed: the streams the end held something for, and those
     * whose bytes a read handed its caller, who had not taken them. Settled with the end, before Closed; a stream leaves
     * it once a read of it returned with nothing more owed, or its caller let it go.
     */
    val unread = MutableStateFlow<QuicUnreadAtClose>(QuicUnreadAtClose.ConnectionOpen)

    /** Inside only: the reads that handed their caller bytes it has not taken yet. */
    val handed = HandedReads()

    /** The connection's idle read buffer; freed once the connection ends. */
    val reads = IdleRead()

    fun end(): EdgeEnd = endCell.load()

    override fun established(report: ConnectReport) {
        val facts = report.facts
        when (val home = chain) {
            is ChainHome.NotKept -> report.chain.free()
            is ChainHome.Kept -> home.chain.keep(report.chain)
        }
        settled(facts)
        publish(QuicConnectionState.Established(facts.alpn, facts.resumption))
        start.value = HandlerStart.Now
    }

    override fun earlyData(report: EarlyReport) {
        val alpn = alpnOf(report.alpnLength)
        handshake.value = HandshakeRecord.Settled(alpn, report.resumption)
        earlyData.value = EarlyDataAcceptance.Accepted(alpn, report.resumption)
        start.value = HandlerStart.Now
    }

    override fun incoming(
        stream: QuicStreamId,
        arrival: Arrival,
    ) {
        incoming.offer(PeerStream(stream.id, arrival))
    }

    /**
     * Kept with the protocol the handshake negotiated, which is what an offer of it will speak; kept for the next
     * connection before it is published, so a caller that sees it can reconnect with it kept.
     */
    override fun ticket(
        bytes: NativeBytes,
        length: Int,
    ) {
        val protocol =
            when (val now = state.value) {
                is QuicConnectionState.Established -> now.negotiatedAlpn
                is QuicConnectionState.Idle, is QuicConnectionState.Handshaking, is QuicConnectionState.Draining,
                is QuicConnectionState.Closed,
                -> return
            }
        if (protocol.isEmpty()) return
        val serialized = bytes.owner
        serialized.position(0)
        serialized.setLimit(length)
        val issued = QuicSessionTicket.restore(serialized, protocol)
        tickets.keep(issued)
        ticket.value = QuicSessionTicketState.Issued(issued)
    }

    private fun alpnOf(length: Int): String {
        alpn.position(0)
        alpn.setLimit(length)
        return alpn.readString(length, Charset.UTF8)
    }

    /**
     * The end is published, in [end] and in the public state, before any waiter is woken: closing [incoming] and completing
     * [closed] resume callers, and a resumed caller may run on its own thread at once, so whatever it reads must
     * already say how the connection ended.
     */
    override fun ended(
        end: ConnEnd,
        held: HeldReads,
        outcome: CloseOutcome,
        handshake: HandshakeFate,
    ) {
        val ended = EdgeEnd.Ended(end, held, outcome, handshake)
        when (handshake) {
            is HandshakeFate.Completed -> settled(handshake.facts)
            is HandshakeFate.NotCompleted -> Unit
        }
        endCell.store(ended)
        reads.close()
        val inHand = handed.atEnd()
        val owing = held.owing() + inHand.streams()
        unread.value = if (owing.isEmpty()) QuicUnreadAtClose.AllRead else QuicUnreadAtClose.Unread(owing)
        // A caller that took its bytes before the line above found nothing to leave yet: it leaves here.
        inHand.forEachTaken(::returned)
        val reason = closeReasonOf(end)
        publish(QuicConnectionState.Closed(reason))
        start.compareAndSet(HandlerStart.NotYet, startAtEnd(handshake))
        wake(ended)
    }

    /**
     * Fails a parked accept and every later one, and returns every waiting `close()`; only after the end is published.
     * [closed] completes first, so whoever the incoming streams' close wakes finds the end already there to await.
     */
    private fun wake(ended: EdgeEnd.Ended) {
        closed.complete(ended)
        incoming.close(endedWith(ended.end, QuicCloseCircumstance.Ended))
    }

    /** Any thread, after the end: what a read of [stream] gets next. */
    fun take(stream: QuicStreamId): HeldTake =
        when (val now = endCell.load()) {
            is EdgeEnd.Open -> HeldTake.Closed
            is EdgeEnd.Ended -> now.held.take(stream)
        }

    /** Any thread: a read of [stream] returned to its caller; the stream leaves [unread] once it owes nothing more. */
    fun returned(stream: QuicStreamId) {
        val now = endCell.load()
        if (now is EdgeEnd.Ended && now.held.owed(stream) == Owed.No) leave(stream)
    }

    /** Any thread: the caller let [stream] go; what was held for it is freed. */
    fun letGo(stream: QuicStreamId) {
        val now = endCell.load()
        if (now !is EdgeEnd.Ended) return
        now.held.free(stream)
        leave(stream)
    }

    /** The caller's close: once the connection has ended, the caller is done with it, and what no read took is freed. */
    suspend fun closedByCaller(): EdgeEnd.Ended = closed.await().also { letGoAll() }

    /** Any thread: the caller is done with the ended connection; what it held that no read took is freed. */
    private fun letGoAll() {
        when (val now = endCell.load()) {
            is EdgeEnd.Ended -> now.held.freeAll()
            is EdgeEnd.Open -> Unit
        }
    }

    private fun leave(stream: QuicStreamId) =
        unread.update { now -> if (now is QuicUnreadAtClose.Unread && stream in now.streams) now.without(stream) else now }

    /** What the handshake settled is kept: the same facts, from the report or from the end. */
    private fun settled(facts: HandshakeFacts) {
        handshake.value = HandshakeRecord.Settled(facts.alpn, facts.resumption)
    }

    /** A handler starts for a connection whose handshake completed, whatever ended it after. */
    private fun startAtEnd(handshake: HandshakeFate): HandlerStart =
        when (handshake) {
            is HandshakeFate.Completed -> HandlerStart.Now
            is HandshakeFate.NotCompleted -> HandlerStart.Never
        }

    private fun publish(now: QuicConnectionState) {
        state.value = now
        capture.record { it.connectionState(now) }
    }
}
