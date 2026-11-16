@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.ConnSink
import com.ditchoom.socket.quic.core.conn.ConnectReport
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Published
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

/**
 * What callers of one connection see: its public state, the streams the peer opened and the end. The connection's
 * [ConnSink]: told inside the connection, it only publishes, and runs no caller code.
 */
@Published
internal class EdgeState(
    /** Where the connection copies the negotiated ALPN; read here once, at establishment. */
    private val alpn: PlatformBuffer,
    private val capture: TraceCapture,
) : ConnSink {
    private val endCell = AtomicReference<EdgeEnd>(EdgeEnd.Open)
    val state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)

    /** Ids of the streams the peer opened, oldest first. */
    val incoming = Channel<Long>(Channel.UNLIMITED)
    val closed = CompletableDeferred<QuicCloseReason>()

    fun end(): EdgeEnd = endCell.load()

    override fun established(report: ConnectReport) {
        alpn.position(0)
        alpn.setLimit(report.alpnLength)
        publish(QuicConnectionState.Established(alpn.readString(report.alpnLength, Charset.UTF8)))
    }

    override fun incoming(stream: QuicStreamId) {
        incoming.trySend(stream.id)
    }

    /**
     * The end is published, in [end] and in the public state, before any waiter is woken: closing [incoming] and completing
     * [closed] resume callers, and a resumed caller may run on its own thread at once, so whatever it reads must
     * already say how the connection ended.
     */
    override fun ended(end: ConnEnd) {
        endCell.store(EdgeEnd.Ended(end))
        val reason = closeReasonOf(end)
        publish(QuicConnectionState.Closed(reason))
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
