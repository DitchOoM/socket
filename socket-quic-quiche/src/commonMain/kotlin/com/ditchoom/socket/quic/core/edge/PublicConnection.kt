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
import com.ditchoom.socket.quic.QuicDatagramSender
import com.ditchoom.socket.quic.QuicHandshakeFacts
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicSendStream
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.StreamOpen
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.quic.core.sched.HandlerSite
import com.ditchoom.socket.quic.core.stream.StreamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * This connection as the public [QuicConnection], once its handshake completed; a connection that ended since is handed
 * out all the same. Raises the end of a connection whose handshake never completed.
 */
internal suspend fun CoreConnection.public(): CoreQuicConnection {
    established()
    val settled = edge.handshake.filterIsInstance<HandshakeRecord.Settled>().first()
    return CoreQuicConnection(this, QuicHandshakeFacts(settled.negotiatedAlpn, settled.resumption, settled.leaf), codes)
}

/** A [CoreConnection] as the public [QuicConnection]: its streams in their public shapes, and its outcomes as values. */
internal class CoreQuicConnection(
    private val core: CoreConnection,
    override val handshake: QuicHandshakeFacts,
    private val codes: QuicAbortCodes,
) : QuicConnection {
    private val streams = PublicStreams(core, codes)

    /** The streams opened in the plain form, which the connection's block cuts when it ends. */
    private val plain = PlainStreams()

    override val identity: QuicConnectionIdentity get() = core.identity
    override val bufferFactory: BufferFactory get() = core.bufferFactory
    override val datagrams: QuicDatagramSender get() = core.coreDatagrams

    /**
     * Runs [block] as this connection's block. When it ends, the plain-form streams still open are cut, and the
     * connection closes: gracefully after a return, at once after a throw or a cancellation.
     */
    suspend fun <R> asBlock(block: suspend (QuicConnection) -> R): R {
        var returned = Returned.No
        try {
            return block(this).also { returned = Returned.Yes }
        } finally {
            withContext(NonCancellable) {
                plain.blockEnded()
                when (returned) {
                    Returned.Yes -> close(QuicCloseChoice.Graceful())
                    Returned.No -> close(QuicCloseChoice.Immediate())
                }
            }
        }
    }

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

    override suspend fun openStream(): StreamOpen<QuicStream> = bidirectional().also { it.fold(plain::opened) { } }

    override suspend fun <R> openStream(block: suspend (QuicStream) -> R): StreamOpen<R> =
        placed {
            bidirectional().fold(
                { stream ->
                    StreamOpen.opened(owning({ stream.abandon(codes.handlerFailed) }) { block(stream).also { stream.complete() } })
                },
                { StreamOpen.notOpened(it) },
            )
        }

    override suspend fun openSendStream(): StreamOpen<QuicSendStream> = sendOnly().also { it.fold(plain::opened) { } }

    override suspend fun <R> openSendStream(block: suspend (QuicSendStream) -> R): StreamOpen<R> =
        placed {
            sendOnly().fold(
                { stream ->
                    StreamOpen.opened(owning({ stream.reset(codes.handlerFailed) }) { block(stream).also { stream.complete() } })
                },
                { StreamOpen.notOpened(it) },
            )
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

    private suspend fun bidirectional(): StreamOpen<CoreBidiStream> =
        when (val open = core.opened(StreamKind.Bidirectional)) {
            is OpenSettled.Stream -> StreamOpen.opened(streams.both(core.halves.openedBoth(open.id), Arrival.Confirmed))
            is OpenSettled.NotOpened -> StreamOpen.notOpened(notOpened(open.answer))
        }

    private suspend fun sendOnly(): StreamOpen<CoreSendStream> =
        when (val open = core.opened(StreamKind.Unidirectional)) {
            is OpenSettled.Stream -> StreamOpen.opened(streams.sending(core.halves.openedSendOnly(open.id)))
            is OpenSettled.NotOpened -> StreamOpen.notOpened(notOpened(open.answer))
        }

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

/** What a refused open is to its caller: the connection's end or a passed bound as a value, or a failure of the open's own, which raises. */
private fun notOpened(answer: OpenAnswer.Failed): StreamOpen.NotOpened =
    when (val failure = answer.failure) {
        is OpFailure.DeadlinePassed -> StreamOpen.NotOpened.DeadlinePassed
        is Raised.ConnEnded -> StreamOpen.NotOpened.ConnectionEnded(publicEndOf(failure.end))
        is Raised.PastClose -> StreamOpen.NotOpened.ConnectionEnded(publicEndOf(failure.end))
        is Raised.WriteRefused, is Raised.ReadRefused, is Raised.Thrown, is Raised.Typed, is Raised.StreamClosed,
        is Raised.NoSuchDirection, is Raised.NotNative, is Raised.FactoryNotNative, is Raised.RouteUnresolved,
        is Raised.LoopsClosed, is Raised.StreamsHandled, is Raised.SocketRefused, is Raised.SpecLayoutRefused,
        is Raised.NetworkRefused, is Raised.AccessDenied, is Raised.ChainRefused,
        -> raise(failure, answer.during)
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
