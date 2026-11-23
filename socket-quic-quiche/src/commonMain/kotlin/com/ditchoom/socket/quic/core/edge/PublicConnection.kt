package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.HandshakeRecord
import com.ditchoom.socket.quic.QuicAbortCodes
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseOutcome
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicConnectionLife
import com.ditchoom.socket.quic.QuicHandshakeFacts
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicSendStream
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.quic.core.sched.HandlerSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * This connection as the public [QuicConnection], once its handshake completed; a connection that ended since is handed
 * out all the same. Raises the end of a connection whose handshake never completed.
 */
internal suspend fun CoreConnection.public(codes: QuicAbortCodes = QuicAbortCodes()): QuicConnection {
    established()
    val settled = edge.handshake.filterIsInstance<HandshakeRecord.Settled>().first()
    return CoreQuicConnection(this, QuicHandshakeFacts(settled.negotiatedAlpn, settled.resumption), codes)
}

/** A [CoreConnection] as the public [QuicConnection]: its streams in their public shapes, and its outcomes as values. */
internal class CoreQuicConnection(
    private val core: CoreConnection,
    override val handshake: QuicHandshakeFacts,
    private val codes: QuicAbortCodes,
) : QuicConnection {
    private val streams = PublicStreams(core, codes)

    override val identity: QuicConnectionIdentity get() = core.identity
    override val bufferFactory: BufferFactory get() = core.bufferFactory

    override fun life(): QuicConnectionLife =
        when (val end = core.end()) {
            is EdgeEnd.Open -> QuicConnectionLife.Open
            is EdgeEnd.Ended -> QuicConnectionLife.Ended(publicEndOf(end.end))
        }

    override suspend fun awaitEnd(): QuicConnectionEnd =
        publicEndOf(
            core.edge.closed
                .await()
                .end,
        )

    override suspend fun openStream(): QuicStream = bidirectional()

    override suspend fun <R> openStream(block: suspend (QuicStream) -> R): R =
        placed {
            val stream = bidirectional()
            owning({ stream.abandon(codes.handlerFailed) }) { block(stream).also { stream.complete() } }
        }

    override suspend fun openSendStream(): QuicSendStream = sendOnly()

    override suspend fun <R> openSendStream(block: suspend (QuicSendStream) -> R): R =
        placed {
            val stream = sendOnly()
            owning({ stream.reset(codes.handlerFailed) }) { block(stream).also { stream.complete() } }
        }

    override suspend fun handleStreams(handler: suspend (QuicIncomingStream) -> Unit): QuicConnectionEnd =
        when (val handled = core.handleStreams(HandlerResetCode(codes.handlerFailed), streams::incoming) { handle(it, handler) }) {
            is StreamsHandled.Ended -> publicEndOf(handled.end)
            is StreamsHandled.AlreadyHandling -> raise(Raised.StreamsHandled, QuicCloseCircumstance.Ended)
        }

    override suspend fun close(choice: QuicCloseChoice): QuicCloseOutcome =
        when (val outcome = core.close(coreChoiceOf(choice))) {
            is CloseOutcome.Drained -> QuicCloseOutcome.Drained
            is CloseOutcome.LimitReached -> QuicCloseOutcome.LimitReached()
            is CloseOutcome.Immediate -> QuicCloseOutcome.Immediate
            is CloseOutcome.EndedFirst -> QuicCloseOutcome.EndedFirst(publicEndOf(outcome.end))
            is CloseOutcome.NotAsked -> QuicCloseOutcome.EndedFirst(awaitEnd())
        }

    override suspend fun stats(): QuicStatsRead = core.stats()

    private suspend fun bidirectional() = streams.both(core.halves.openBidirectional(), Arrival.Confirmed)

    private suspend fun sendOnly() = streams.sending(core.halves.openSendOnly())

    /** Runs [handler] on [stream], then completes the stream it returned from; one it throws from is reset by its caller. */
    private suspend fun handle(
        stream: CoreIncoming,
        handler: suspend (QuicIncomingStream) -> Unit,
    ) {
        when (stream) {
            is CoreBidiStream -> {
                handler(stream)
                stream.complete()
            }
            is CoreReceiveStream -> {
                handler(stream)
                stream.complete()
            }
        }
    }

    /** Runs [block] on this connection's lane of its endpoint's handler pool, or on the default dispatcher once the endpoint stopped. */
    private suspend fun <R> placed(block: suspend () -> R): R =
        when (val entry = core.handlers.enter()) {
            is LaneEntry.Entered ->
                try {
                    withContext(entry.lane + HandlerSite.Unnamed) { block() }
                } finally {
                    core.handlers.left()
                }
            is LaneEntry.Refused -> withContext(Dispatchers.Default) { block() }
        }
}

/** Whether a block that owns a stream returned. */
private enum class Returned { No, Yes }

/** Runs [block], which owns a stream; when it throws, [abandon] gives the stream up before the throw goes on. */
private inline fun <R> owning(
    abandon: () -> Unit,
    block: () -> R,
): R {
    var returned = Returned.No
    try {
        return block().also { returned = Returned.Yes }
    } finally {
        when (returned) {
            Returned.Yes -> Unit
            Returned.No -> abandon()
        }
    }
}

/** The longest limit a graceful close is given: a longer one is this long. */
private val LONGEST_LIMIT = 365.days

private fun coreChoiceOf(choice: QuicCloseChoice): CloseChoice =
    when (choice) {
        is QuicCloseChoice.Graceful ->
            CloseChoice.Graceful(choice.limit.coerceIn(Duration.ZERO, LONGEST_LIMIT).inWholeNanoseconds, DrainUntil.Acknowledged)
        is QuicCloseChoice.Immediate -> CloseChoice.Immediate(choice.error)
    }
