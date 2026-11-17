@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.EarlyDataAcceptance
import com.ditchoom.socket.quic.HandlerStart
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.conn.ConnSink
import com.ditchoom.socket.quic.core.conn.ConnectReport
import com.ditchoom.socket.quic.core.conn.EarlyReport
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.trace.record
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** How a connection stands for its callers: open, or ended and how. */
internal sealed interface EdgeEnd {
    data object Open : EdgeEnd

    class Ended(
        val end: ConnEnd,
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
) : ConnSink {
    private val endCell = AtomicReference<EdgeEnd>(EdgeEnd.Open)
    val state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)

    /** The streams the peer opened, oldest first. */
    val incoming = Channel<PeerStream>(Channel.UNLIMITED)
    val closed = CompletableDeferred<QuicCloseReason>()

    /** A server's: whether it accepted the client's 0-RTT before the handshake completed. */
    val earlyData = MutableStateFlow<EarlyDataAcceptance>(EarlyDataAcceptance.None)

    /** A server's: when the handler of this connection starts. */
    val start = MutableStateFlow<HandlerStart>(HandlerStart.NotYet)

    /** A client's: the session ticket its server issued. */
    val ticket = MutableStateFlow<QuicSessionTicketState>(QuicSessionTicketState.NotIssued)

    /** The connection's idle read buffer; freed once the connection ends. */
    val reads = IdleRead()

    fun end(): EdgeEnd = endCell.load()

    override fun established(report: ConnectReport) {
        publish(QuicConnectionState.Established(alpnOf(report.alpnLength), report.resumption))
        start.value = HandlerStart.Now
    }

    override fun earlyData(report: EarlyReport) {
        earlyData.value = EarlyDataAcceptance.Accepted(alpnOf(report.alpnLength), report.resumption)
        start.value = HandlerStart.Now
    }

    override fun incoming(
        stream: QuicStreamId,
        arrival: Arrival,
    ) {
        incoming.trySend(PeerStream(stream.id, arrival))
    }

    /** Kept with the protocol the handshake negotiated, which is what an offer of it will speak. */
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
        ticket.value = QuicSessionTicketState.Issued(QuicSessionTicket.restore(serialized, protocol))
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
    override fun ended(end: ConnEnd) {
        endCell.store(EdgeEnd.Ended(end))
        reads.close()
        val reason = closeReasonOf(end)
        publish(QuicConnectionState.Closed(reason))
        start.compareAndSet(HandlerStart.NotYet, HandlerStart.Never)
        wake(end, reason)
    }

    /** Fails a parked accept and every later one, and returns every waiting `close()`; only after the end is published. */
    private fun wake(
        end: ConnEnd,
        reason: QuicCloseReason,
    ) {
        incoming.close(endedWith(end, QuicCloseCircumstance.Ended))
        closed.complete(reason)
    }

    private fun publish(now: QuicConnectionState) {
        state.value = now
        capture.record { it.connectionState(now) }
    }
}
