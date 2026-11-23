package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import kotlin.jvm.JvmInline

/**
 * A stream the peer opened, as [QuicConnection.handleStreams] hands it over: a [QuicStream] with both directions, or a
 * [QuicReceiveStream] the peer only sends on.
 */
sealed interface QuicIncomingStream {
    val id: QuicStreamId
}

/**
 * The sending direction of a stream. Every call suspends, never blocks a thread, and is safe from a main thread.
 *
 * A write that is not [StreamWrite.Written] ends this direction: every later [write], [writeAndFinish] and [finish]
 * answers the same [StreamWrite.NotWritten] at once. So a sender may write without looking and read [finish]'s answer.
 */
interface QuicSendStream {
    val id: QuicStreamId

    /** How long a write waits for the peer's flow control when the call names no other policy. */
    val writePolicy: WritePolicy

    /**
     * Writes every remaining byte of [buffer], suspending while the peer's flow control or the congestion window has no
     * room. [buffer] is read in place and stays the caller's; it must be native memory.
     *
     * @throws QuicNativeMemoryRequiredException when [buffer] is not native memory; nothing was written.
     * @throws QuicStreamClosedException after [finish], [writeAndFinish] or [reset].
     */
    suspend fun write(
        buffer: ReadBuffer,
        policy: WritePolicy = writePolicy,
    ): StreamWrite

    /**
     * [write], then [finish], with the stream's end carried by the last bytes: a message that fits one packet leaves in
     * one.
     */
    suspend fun writeAndFinish(
        buffer: ReadBuffer,
        policy: WritePolicy = writePolicy,
    ): StreamWrite

    /**
     * Ends this direction: the peer reads everything written, then the end. Answers [StreamWrite.Written] when every
     * write before it was, and the failure that ended this direction otherwise. Finishing twice answers the same.
     */
    suspend fun finish(): StreamWrite

    /** Abandons this direction with [code] (RESET_STREAM): the peer may never read what was written. Once only. */
    fun reset(code: QuicAppErrorCode)
}

/** The receiving direction of a stream. Every call suspends, never blocks a thread, and is safe from a main thread. */
interface QuicReceiveStream : QuicIncomingStream {
    /** How long a read waits for bytes when the call names no other policy. */
    val readPolicy: ReadPolicy

    /** Whether what arrives on this stream can be a 0-RTT replay. */
    val origin: QuicStreamOrigin

    /**
     * Lets reads of a [QuicStreamOrigin.Early] stream run before the handshake is confirmed. Call it only when handling
     * the stream's bytes twice is harmless. Nothing changes on a [QuicStreamOrigin.Confirmed] stream.
     */
    fun markReplaySafe()

    /**
     * Waits for the stream's next bytes and runs [block] on them. The buffer is the stream's and is valid only while
     * [block] runs: copy or decode what must outlive it. Bytes [block] leaves unread are dropped.
     *
     * A stream has one reader at a time. On a [QuicStreamOrigin.Early] stream not marked replay-safe, the first read
     * waits for the handshake to be confirmed.
     *
     * @throws QuicStreamClosedException after [stop].
     */
    suspend fun <R> read(
        policy: ReadPolicy = readPolicy,
        block: suspend (ReadBuffer) -> R,
    ): ScopedRead<R>

    /** Reads no more and tells the peer to stop sending, with [code] (STOP_SENDING). Once only. */
    fun stop(code: QuicAppErrorCode)
}

/**
 * A bidirectional stream: its two directions, each used on its own.
 *
 * ```
 * val (send, receive) = connection.openStream().getOrElse { why -> return failed(why) }
 * ```
 */
interface QuicStream : QuicIncomingStream {
    val send: QuicSendStream
    val receive: QuicReceiveStream

    operator fun component1(): QuicSendStream = send

    operator fun component2(): QuicReceiveStream = receive
}

/** Runs [block] on each chunk of the stream until a read has no bytes, and returns why it had none. */
suspend fun QuicReceiveStream.readEach(block: suspend (ReadBuffer) -> Unit): ScopedRead.NoData {
    while (true) {
        read(block = block).fold({ }, { return it })
    }
}

/** What a write on a [QuicSendStream] came to. No outcome of a write is an exception. */
sealed interface StreamWrite {
    /** The QUIC stack took every byte, and the stream's end when the call asked for it. */
    data object Written : StreamWrite

    /** Not everything was taken, and the sending direction is over. */
    sealed interface NotWritten : StreamWrite {
        /** The peer asked this side to stop sending, with [code] (STOP_SENDING). */
        class PeerStopped(
            val code: QuicAppErrorCode,
        ) : NotWritten {
            override fun toString(): String = "PeerStopped($code)"
        }

        /** The connection ended, or passed its close point, with [end]. */
        class ConnectionEnded(
            val end: QuicConnectionEnd,
        ) : NotWritten {
            override fun toString(): String = "ConnectionEnded($end)"
        }

        /**
         * The write's deadline passed after the QUIC stack took [bytesTaken] bytes of it. The direction was reset, so the
         * peer never reads the part as the whole.
         */
        class DeadlinePassed(
            val bytesTaken: Long,
        ) : NotWritten {
            override fun toString(): String = "DeadlinePassed($bytesTaken)"
        }
    }
}

/**
 * Which streams a connection lets its peer open, chosen at connect and at serve. A client refuses by default and a
 * server accepts.
 */
sealed interface IncomingStreams {
    /** The peer is told it may open no stream, so none arrives and nothing is held. */
    data object Refuse : IncomingStreams

    /**
     * The peer may have [bidirectional] streams and [unidirectional] streams open at once. Streams that arrive before
     * [QuicConnection.handleStreams] is called wait for it, within these limits.
     */
    data class Accept(
        val bidirectional: QuicStreamLimit = QuicStreamLimit.Default,
        val unidirectional: QuicStreamLimit = QuicStreamLimit.Default,
    ) : IncomingStreams
}

/** How many streams of one kind a peer may have open at once. */
@JvmInline
value class QuicStreamLimit(
    val streams: UInt,
) {
    companion object {
        val Default = QuicStreamLimit(100u)
    }
}

/**
 * The application error codes the library resets a stream with when it must do so itself. They are the application
 * protocol's codes, so the protocol chooses them.
 */
class QuicAbortCodes(
    /** A stream whose handler threw. */
    val handlerFailed: QuicAppErrorCode = QuicAppErrorCode(0x2L),
    /** A sending direction whose write stopped part way. */
    val writeCut: QuicAppErrorCode = QuicAppErrorCode(0x0L),
    /** A stream that carried a frame a typed read refused. */
    val brokenFrame: QuicAppErrorCode = QuicAppErrorCode(0x1L),
    /** A receiving direction its handler returned from before reading it to the end. */
    val leftUnread: QuicAppErrorCode = QuicAppErrorCode(0x0L),
)
