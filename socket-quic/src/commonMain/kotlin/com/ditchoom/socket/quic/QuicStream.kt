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

    /**
     * Queues as much of [bytes] as the stream's send queue has room for, and returns at once: never suspends, never
     * blocks, and is safe from any thread, a [QuicStreamReceiver]'s callback included. [bytes] moves past what was taken
     * and stays the caller's; the queue holds a copy. [write] is this in a loop with [StreamSend.NotSent.WindowFull.awaitRoom].
     *
     * One sender at a time: sends from two threads at once may interleave their bytes.
     */
    fun trySend(bytes: ReadBuffer): StreamSend

    /** Queues our FIN after the bytes queued before it, and returns at once, as [trySend] does. */
    fun tryFinish(): StreamSend

    /**
     * [trySend] of the stream's [lastBytes] with our FIN: when all of them fit, the FIN is queued in the same step, so it
     * leaves with them, in their packet. When only some fit, the FIN is not queued: the answer is
     * [StreamSend.NotSent.WindowFull] and the call is made again with the rest. [writeAndFinish] is this in a loop.
     */
    fun tryFinish(lastBytes: ReadBuffer): StreamSend
}

/**
 * What a [QuicSendStream.trySend] or [QuicSendStream.tryFinish] came to. Each is made once per stream, so a sender that
 * is told again and again allocates nothing; only [NotSent.WindowFull.awaitRoom] suspends.
 *
 * ```
 * when (val r = send.trySend(bytes)) {
 *     StreamSend.Sent -> Unit
 *     is StreamSend.NotSent.WindowFull -> r.awaitRoom()   // bytes moved past what was taken; send the rest after
 *     is StreamSend.NotSent.Over -> stop(r)               // Finished, PeerStopped or ConnectionEnded
 * }
 * ```
 */
sealed interface StreamSend {
    /** Every byte was queued, in order after the bytes queued before them, or the FIN was. */
    data object Sent : StreamSend

    sealed interface NotSent : StreamSend {
        /**
         * The send queue had room for part of the bytes or for none: the buffer moved past what was taken. Wait with
         * [awaitRoom] and send the rest, or stop. The queue empties as the peer's flow control and the congestion
         * window let the connection send.
         */
        class WindowFull(
            private val room: StreamRoomWait,
        ) : NotSent {
            /**
             * Suspends until the send queue has room again, the direction is over, or [policy]'s deadline passes. A
             * passed deadline leaves the direction open: what was queued still goes.
             */
            suspend fun awaitRoom(policy: WritePolicy = WritePolicy.UntilClosed): StreamRoom = room.awaitRoom(policy)
        }

        /** The direction is over: nothing more is queued, ever. */
        sealed interface Over : NotSent

        /** This side ended the direction: it finished or reset it. */
        data object Finished : Over

        /** The peer asked this side to stop sending, with [code] (STOP_SENDING). */
        class PeerStopped(
            val code: QuicAppErrorCode,
        ) : Over {
            override fun toString(): String = "PeerStopped($code)"
        }

        /** The connection ended, or passed its close point, with [end]. */
        class ConnectionEnded(
            val end: QuicConnectionEnd,
        ) : Over {
            override fun toString(): String = "ConnectionEnded($end)"
        }
    }
}

/** What [StreamSend.NotSent.WindowFull.awaitRoom] came to. */
sealed interface StreamRoom {
    /** The send queue has room: send again. */
    data object Room : StreamRoom

    /** The deadline passed first; the direction is still open. */
    data object DeadlinePassed : StreamRoom

    /** The direction is over, as [why] says. */
    class Over(
        val why: StreamSend.NotSent.Over,
    ) : StreamRoom {
        override fun toString(): String = "Over($why)"
    }
}

/** How a stream waits for room in its send queue; what [StreamSend.NotSent.WindowFull.awaitRoom] calls. */
fun interface StreamRoomWait {
    suspend fun awaitRoom(policy: WritePolicy): StreamRoom
}

/**
 * Receives a stream's bytes without suspending, registered once with [QuicReceiveStream.receive]. The library calls it
 * on the connection's handler lane: never on the network loop, never on two threads at once, and never at the same time
 * as another handler of the same connection.
 *
 * The bytes [onBytes] is given are lent: they are the stream's own receive buffer, valid only until [onBytes] returns.
 * Copy or decode what must outlive the call. Reply from inside with [QuicSendStream.trySend], which does not suspend.
 *
 * Blocking in [onBytes] holds the stream back, not the network: the stream reads nothing more until [onBytes] returns,
 * so its flow-control window stays shut and the peer can send only what the window already allows. It also holds the
 * connection's handler lane, so the connection's other handlers wait; handlers of other connections do not.
 */
interface QuicStreamReceiver {
    /**
     * The stream's next bytes: what an earlier call left, then everything that arrived since. Lent: valid until this
     * returns. Take what you can by reading [bytes] forward; its position when this returns says how much was taken.
     * The rest is offered again: at once when this call took some, else with the next bytes that arrive or once the
     * stream's own send queue makes room. Until the rest is taken, the peer is held back by flow control.
     */
    fun onBytes(bytes: ReadBuffer)

    /** No more bytes will come, as [end] says. Called once, last; not called after [QuicReceiveStream.stop]. */
    fun onEnd(end: ScopedRead.End)
}

/** What [QuicReceiveStream.receive] came to. */
sealed interface StreamReceive {
    /** The receiver is called for every byte from here on. */
    data object Receiving : StreamReceive

    sealed interface NotReceiving : StreamReceive {
        /** A read or another receiver has the stream: a stream has one reader at a time. */
        data object AlreadyReading : NotReceiving

        /** An early stream not marked replay-safe, before the handshake is confirmed: call [QuicReceiveStream.markReplaySafe] or wait. */
        data object NotReplaySafe : NotReceiving

        /** The stream gives no more, as [end] says. */
        class Ended(
            val end: ScopedRead.End,
        ) : NotReceiving {
            override fun toString(): String = "Ended($end)"
        }
    }
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
     * The core [read] is inlined over; an application calls [read]. Waits for the stream's next bytes and answers
     * either the [ReadBuffer] they are in, which [read] hands to [readDone] once its block returns, or the
     * [ScopedRead.NoData] that says why none came. It answers one of the two as a plain reference, not as a
     * [ScopedRead], because Kotlin boxes a value class returned across a suspension: that would be an object per read.
     *
     * @throws QuicStreamClosedException after [stop].
     * @throws QuicStreamReadException with [QuicStreamReadError.ReadInProgress] for a read made while another is out,
     *   its block included.
     */
    suspend fun readNext(policy: ReadPolicy): Any

    /** The core [read] is inlined over: [read]'s block is done with [bytes], which [readNext] answered. */
    fun readDone(bytes: ReadBuffer)

    /** Reads no more and tells the peer to stop sending, with [code] (STOP_SENDING). Once only. */
    fun stop(code: QuicAppErrorCode)

    /**
     * Hands every byte of this stream from here on to [receiver], on the connection's handler lane, with no suspension:
     * see [QuicStreamReceiver] for which thread calls it, how long its bytes live and what blocking in it does. Once per
     * stream; a stream with a receiver takes no [read]. Safe from any thread.
     */
    fun receive(receiver: QuicStreamReceiver): StreamReceive
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

/**
 * Waits for the stream's next bytes and runs [block] on them. The buffer is the stream's and is valid only while
 * [block] runs: copy or decode what must outlive it. Bytes [block] leaves unread are dropped. Inline, so [block] is
 * no object and its result is not boxed; [block] may suspend.
 *
 * A stream has one reader at a time. On a [QuicStreamOrigin.Early] stream not marked replay-safe, the first read
 * waits for the handshake to be confirmed. While no read waits, nothing is read off the stream, so the peer is held
 * back by flow control until the next read.
 *
 * @throws QuicStreamClosedException after [QuicReceiveStream.stop].
 * @throws QuicStreamReadException with [QuicStreamReadError.ReadInProgress] for a read made while another is out,
 *   its block included.
 */
suspend inline fun <R> QuicReceiveStream.read(
    policy: ReadPolicy = readPolicy,
    block: (ReadBuffer) -> R,
): ScopedRead<R> =
    ScopedRead<ReadBuffer>(readNext(policy)).fold(
        { bytes ->
            try {
                ScopedRead.data(block(bytes))
            } finally {
                readDone(bytes)
            }
        },
        { ScopedRead.noData(it) },
    )

/** Runs [block] on each chunk of the stream until a read has no bytes, and returns why it had none. */
suspend inline fun QuicReceiveStream.readEach(block: (ReadBuffer) -> Unit): ScopedRead.NoData {
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
 * Which streams a connection lets its peer open, set in [QuicOptions.incomingStreams]: the one source of the stream
 * limits an endpoint advertises (RFC 9000 `initial_max_streams_bidi` and `_uni`).
 */
sealed interface IncomingStreams {
    /**
     * [Refuse] on a client and [Accept] with its defaults on a server: the default. The legacy `QuicScope` entry points
     * read it as [Accept] on both sides.
     */
    data object ByRole : IncomingStreams

    /** The peer is told it may open no stream: both limits are advertised as zero, so none arrives and nothing is held. */
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
) {
    override fun equals(other: Any?): Boolean =
        other is QuicAbortCodes &&
            handlerFailed == other.handlerFailed &&
            writeCut == other.writeCut &&
            brokenFrame == other.brokenFrame &&
            leftUnread == other.leftUnread

    override fun hashCode(): Int =
        ((handlerFailed.hashCode() * 31 + writeCut.hashCode()) * 31 + brokenFrame.hashCode()) * 31 + leftUnread.hashCode()
}
