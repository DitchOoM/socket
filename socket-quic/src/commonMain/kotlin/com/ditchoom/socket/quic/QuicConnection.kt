package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A QUIC connection whose handshake completed. Every call suspends, never blocks a thread, and is safe from a main
 * thread; a call resumes on its caller's dispatcher.
 *
 * A connection the peer has already closed is still handed out: its first read or write answers the end, and
 * [handshake] stays readable.
 */
interface QuicConnection {
    val identity: QuicConnectionIdentity

    /** What the completed handshake settled. Readable for as long as the connection is referenced, ended or not. */
    val handshake: QuicHandshakeFacts

    /** Makes the native memory a [QuicSendStream] writes from. */
    val bufferFactory: BufferFactory

    /** Whether the connection is open, or how it ended. */
    fun life(): QuicConnectionLife

    /** Suspends until the connection has ended, and returns how. */
    suspend fun awaitEnd(): QuicConnectionEnd

    /**
     * Opens a bidirectional stream, waiting at the peer's stream limit as the connection's [StreamOpenPolicy] says, and
     * answers the stream or why none was opened. The stream is reset with the connection if it is still open when the
     * connection's block ends.
     */
    suspend fun openStream(): StreamOpen<QuicStream>

    /**
     * Opens a bidirectional stream and runs [block] on it, placed by the library; answers [block]'s value, or why no
     * stream was opened, in which case [block] did not run. When [block] returns, a sending direction still open is
     * finished and a receiving direction not read to its end is stopped; when it throws, both are reset with
     * [QuicAbortCodes.handlerFailed] and the exception reaches the caller.
     */
    suspend fun <R> openStream(block: suspend (QuicStream) -> R): StreamOpen<R>

    /** Opens a stream this side only sends on. Waits and answers as [openStream] does. */
    suspend fun openSendStream(): StreamOpen<QuicSendStream>

    /** Opens a stream this side only sends on and runs [block] on it, as [openStream] does for both directions. */
    suspend fun <R> openSendStream(block: suspend (QuicSendStream) -> R): StreamOpen<R>

    /**
     * Runs [handler] once for every stream the peer opens, placed by the library, and returns how the connection ended
     * once it has and every handler has returned. Handlers of one connection never run at the same time.
     *
     * When [handler] returns, the stream is completed as [openStream]'s block form completes it; when it throws, the
     * stream is reset with [QuicAbortCodes.handlerFailed], and the other streams and the connection go on. Once the
     * connection ends no stream call waits, and each handler runs to its own return.
     *
     * @throws QuicStreamsAlreadyHandledException when another call is handling this connection's streams.
     */
    suspend fun handleStreams(handler: suspend (QuicIncomingStream) -> Unit): QuicConnectionEnd

    /**
     * Closes the connection as [choice] says and returns how the close went, once the connection has ended. A write, an
     * open or a datagram that comes after this call is refused at once.
     */
    suspend fun close(choice: QuicCloseChoice = QuicCloseChoice.Graceful()): QuicCloseOutcome

    /** The connection's counters and its active path's estimates. Nothing is counted for it while nobody asks. */
    suspend fun stats(): QuicStatsRead
}

/** What a connection's completed handshake settled. */
class QuicHandshakeFacts(
    /** The one protocol both sides agreed on (RFC 7301). */
    val negotiatedAlpn: String,
    /** How the handshake used a session ticket. */
    val resumption: QuicResumptionOutcome,
)

/** Whether a connection is open, or how it ended. */
sealed interface QuicConnectionLife {
    data object Open : QuicConnectionLife

    class Ended(
        val end: QuicConnectionEnd,
    ) : QuicConnectionLife
}

/** How [QuicConnection.close] closes. */
sealed interface QuicCloseChoice {
    /**
     * Delivers everything written before the close, then closes with no error: as soon as the peer has acknowledged it,
     * and [limit] after the close at the latest. A [limit] that is not positive is reached at once.
     */
    class Graceful(
        val limit: Duration = DEFAULT_LIMIT,
    ) : QuicCloseChoice

    /** Closes at once with [error]; what was not delivered is abandoned. Cuts short a graceful close that is waiting. */
    class Immediate(
        val error: QuicError = QuicError.NoError,
    ) : QuicCloseChoice

    companion object {
        /** The longest a graceful close waits unless the caller says otherwise. */
        val DEFAULT_LIMIT: Duration = 10.seconds
    }
}

/** How a [QuicConnection.close] went. */
sealed interface QuicCloseOutcome {
    /** Everything written before the close was acknowledged, and then the connection closed. */
    data object Drained : QuicCloseOutcome

    /** The graceful close's limit passed first: the connection closed then, and what was undelivered is abandoned. */
    class LimitReached : QuicCloseOutcome

    /** The connection closed at once: an immediate close, or one that cut a graceful close short. */
    data object Immediate : QuicCloseOutcome

    /** The connection had ended, or was ending, another way: [end] says how. */
    class EndedFirst(
        val end: QuicConnectionEnd,
    ) : QuicCloseOutcome
}

/** A second [QuicConnection.handleStreams] while a first is still handling the connection's streams. */
class QuicStreamsAlreadyHandledException : IllegalStateException("this connection's streams are already handled")
