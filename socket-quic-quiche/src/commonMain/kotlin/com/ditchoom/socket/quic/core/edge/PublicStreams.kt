@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicAbortCodes
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamOrigin
import com.ditchoom.socket.quic.QuicStreamReceiver
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamReceive
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.conn.HandshakeFate
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Published
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A stream the peer opened, in the public shape its handler is given: both directions, or the receiving one alone. */
internal sealed interface CoreIncoming

/** Where a caller's reading of a public stream stands. */
private sealed interface Reading {
    /** An early stream: its first read waits for the handshake, unless it is marked replay-safe first. */
    data object Gated : Reading

    data object Open : Reading

    /** Reads, one at a time, through the stream's [inbox]. */
    class Reads(
        val inbox: ReadInbox,
    ) : Reading

    /** A read answered that the stream gives no more: [end], which every read after answers too. */
    class AtEnd(
        val end: ScopedRead.End,
    ) : Reading

    companion object {
        /** The end most streams meet, made once. */
        val FINISHED = AtEnd(ScopedRead.End.PeerFinished)
    }

    /** A receiver has the stream for good: no read is taken. */
    data object Receiver : Reading
}

/** Where reading stands once a read met [end]. */
private fun atEnd(end: ScopedRead.End): Reading.AtEnd =
    when (end) {
        is ScopedRead.End.PeerFinished -> Reading.FINISHED
        is ScopedRead.End.PeerReset, is ScopedRead.End.ConnectionEnded -> Reading.AtEnd(end)
    }

/** A [ReceiveHalf] as the public [QuicReceiveStream]: every outcome of a read is a value. */
@Published
internal class CoreReceiveStream(
    private val connection: CoreConnection,
    private val half: ReceiveHalf,
    arrival: Arrival,
    private val codes: QuicAbortCodes,
    private val replies: RoomSource,
) : QuicReceiveStream,
    CoreIncoming {
    private val reading =
        AtomicReference(
            when (arrival) {
                Arrival.Confirmed -> Reading.Open
                Arrival.Early -> Reading.Gated
            },
        )

    override val id: QuicStreamId get() = half.id
    override val readPolicy: ReadPolicy get() = half.readPolicy

    override val origin: QuicStreamOrigin =
        when (arrival) {
            Arrival.Confirmed -> QuicStreamOrigin.Confirmed
            Arrival.Early -> QuicStreamOrigin.Early(connection::established)
        }

    override fun markReplaySafe() {
        reading.compareAndSet(Reading.Gated, Reading.Open)
    }

    override suspend fun <R> read(
        policy: ReadPolicy,
        block: suspend (ReadBuffer) -> R,
    ): ScopedRead<R> {
        val inbox =
            when (val now = reading.load()) {
                is Reading.Reads -> now.inbox
                is Reading.AtEnd -> return ScopedRead.noData(now.end)
                is Reading.Receiver -> raise(Raised.StreamClosed(id, QuicStreamSide.Read), QuicCloseCircumstance.Ended)
                is Reading.Open -> inboxFrom(now)
                is Reading.Gated ->
                    when (val end = confirmed()) {
                        is EdgeEnd.Open -> inboxFrom(now)
                        is EdgeEnd.Ended ->
                            when (end.handshake) {
                                is HandshakeFate.Completed -> inboxFrom(now)
                                is HandshakeFate.NotCompleted -> return ended(ScopedRead.End.ConnectionEnded(publicEndOf(end.end)))
                            }
                    }
            }
        if (!half.isOpen()) raise(Raised.StreamClosed(id, QuicStreamSide.Read), QuicCloseCircumstance.Ended)
        val place = connection.placeOf(currentCoroutineContext())
        return when (val next = inbox.next(deadlineOf(policy), place)) {
            is ReadNext.Lent ->
                try {
                    ScopedRead.data(block(next.bytes))
                } finally {
                    inbox.returned()
                }
            is ReadNext.Held ->
                try {
                    ScopedRead.data(block(next.bytes))
                } finally {
                    next.bytes.freeIfNeeded()
                    connection.edge.returned(id)
                }
            is ReadNext.Ended -> ended(next.end)
            is ReadNext.DeadlinePassed -> ScopedRead.noData(ScopedRead.DeadlinePassed)
            is ReadNext.Closed -> raise(Raised.StreamClosed(id, QuicStreamSide.Read), QuicCloseCircumstance.Ended)
            is ReadNext.Busy -> raise(Raised.ReadInProgress(id), QuicCloseCircumstance.Ended)
            is ReadNext.Refused -> raise(next.failure, QuicCloseCircumstance.Ended)
        }
    }

    /** The stream's inbox, made by its first read and kept until the stream is let go. */
    private fun inboxFrom(from: Reading): ReadInbox {
        val made = ReadInbox(connection, id)
        if (reading.compareAndSet(from, Reading.Reads(made))) return made
        made.discard()
        return when (val now = reading.load()) {
            is Reading.Reads -> now.inbox
            is Reading.Gated, is Reading.Open, is Reading.AtEnd, is Reading.Receiver ->
                raise(Raised.StreamClosed(id, QuicStreamSide.Read), QuicCloseCircumstance.Ended)
        }
    }

    override fun stop(code: QuicAppErrorCode) {
        half.stop(code)
        when (val now = reading.load()) {
            is Reading.Reads -> now.inbox.letGo()
            is Reading.Gated, is Reading.Open, is Reading.AtEnd, is Reading.Receiver -> Unit
        }
    }

    override fun receive(receiver: QuicStreamReceiver): StreamReceive {
        val from =
            when (val now = reading.load()) {
                is Reading.AtEnd, is Reading.Receiver -> return StreamReceive.NotReceiving.AlreadyReading
                is Reading.Gated ->
                    when (connection.state.value) {
                        is QuicConnectionState.Established, is QuicConnectionState.Closed -> now
                        is QuicConnectionState.Idle, is QuicConnectionState.Handshaking, is QuicConnectionState.Draining ->
                            return StreamReceive.NotReceiving.NotReplaySafe
                    }
                is Reading.Open, is Reading.Reads -> now
            }
        if (!half.isOpen()) return StreamReceive.NotReceiving.AlreadyReading
        val lane =
            when (val entry = connection.handlers.enter()) {
                is LaneEntry.Entered -> entry.lane
                is LaneEntry.Refused -> return StreamReceive.NotReceiving.Ended(ScopedRead.End.ConnectionEnded(endNow()))
            }
        if (!reading.compareAndSet(from, Reading.Receiver)) {
            connection.handlers.left()
            return StreamReceive.NotReceiving.AlreadyReading
        }
        // Reads went through the stream's read inbox: it lets go, and no bytes are lost, since it holds none unlent.
        when (from) {
            is Reading.Reads -> from.inbox.letGo()
            is Reading.Gated, is Reading.Open, is Reading.AtEnd, is Reading.Receiver -> Unit
        }
        val inbox = StreamInbox(connection, id, lane, receiver, codes.handlerFailed)
        replies.listen(inbox)
        connection.handToLoop(inbox)
        return StreamReceive.Receiving
    }

    /** The connection's end as of now, for a stream that can take no receiver because the endpoint stopped. */
    private fun endNow(): QuicConnectionEnd =
        when (val end = connection.end()) {
            is EdgeEnd.Ended -> publicEndOf(end.end)
            is EdgeEnd.Open -> publicEndOf(ConnEnd.Released)
        }

    /** The block that owned this stream returned: a direction not read to its end is stopped. */
    fun complete() {
        when (val now = reading.load()) {
            is Reading.AtEnd, is Reading.Receiver -> Unit
            is Reading.Gated, is Reading.Open -> half.stop(codes.leftUnread)
            is Reading.Reads -> {
                half.stop(codes.leftUnread)
                now.inbox.letGo()
            }
        }
    }

    /** Whether a read has answered that the stream gives no more. */
    fun readState(): PlainState =
        when (reading.load()) {
            is Reading.AtEnd, is Reading.Receiver -> PlainState.Done
            is Reading.Gated, is Reading.Open, is Reading.Reads -> PlainState.Open
        }

    /** How the connection stands once the handshake an early stream waits for completed, or the connection ended. */
    private suspend fun confirmed(): EdgeEnd {
        connection.state.first { it is QuicConnectionState.Established || it is QuicConnectionState.Closed }
        return connection.end()
    }

    /** A read met the stream's [end]: every read after answers it too. A receiver that took the stream keeps it. */
    private fun ended(end: ScopedRead.End): ScopedRead<Nothing> {
        when (val now = reading.load()) {
            is Reading.Gated, is Reading.Open, is Reading.Reads -> reading.compareAndSet(now, atEnd(end))
            is Reading.AtEnd, is Reading.Receiver -> Unit
        }
        return ScopedRead.noData(end)
    }
}

/** A [BidiStream] as the public [QuicStream]: its two directions. */
internal class CoreBidiStream(
    override val send: CoreSendStream,
    override val receive: CoreReceiveStream,
) : QuicStream,
    CoreIncoming {
    override val id: QuicStreamId get() = send.id

    /** Open while either direction is. */
    fun plainState(): PlainState =
        if (send.plainState() == PlainState.Open || receive.readState() == PlainState.Open) PlainState.Open else PlainState.Done

    /** Its block ended: an open sending direction is reset, a receiving one not read to its end is stopped. */
    fun cut() {
        send.cut()
        receive.complete()
    }

    /** The block that owned this stream returned. */
    suspend fun complete() {
        send.complete()
        receive.complete()
    }

    /** The block that owned this stream threw: both directions are abandoned with [code]. */
    fun abandon(code: QuicAppErrorCode) {
        receive.stop(code)
        send.reset(code)
    }
}

/** A connection's streams in their public shapes. */
internal class PublicStreams(
    private val connection: CoreConnection,
    private val codes: QuicAbortCodes,
) {
    fun sending(half: SendHalf) = CoreSendStream(connection, half, codes)

    fun both(
        stream: BidiStream,
        arrival: Arrival,
    ): CoreBidiStream {
        val send = CoreSendStream(connection, stream.send, codes)
        return CoreBidiStream(send, CoreReceiveStream(connection, stream.receive, arrival, codes, send.outbox))
    }

    fun incoming(stream: IncomingStream): CoreIncoming =
        when (stream) {
            is IncomingStream.Bidirectional -> both(stream.stream, stream.arrival)
            is IncomingStream.ReceiveOnly -> CoreReceiveStream(connection, stream.receive, stream.arrival, codes, NoRoomSource)
        }
}
