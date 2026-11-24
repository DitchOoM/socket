@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.socket.quic.QuicAbortCodes
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicEngineSpi
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamOrigin
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.conn.HandshakeFate
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

    /** A read answered that the stream gives no more. */
    data object AtEnd : Reading
}

/** A [ReceiveHalf] as the public [QuicReceiveStream]: every outcome of a read is a value. */
@Published
internal class CoreReceiveStream(
    private val connection: CoreConnection,
    private val half: ReceiveHalf,
    arrival: Arrival,
    private val codes: QuicAbortCodes,
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

    @OptIn(QuicEngineSpi::class)
    override val origin: QuicStreamOrigin =
        when (arrival) {
            Arrival.Confirmed -> QuicStreamOrigin.Confirmed
            Arrival.Early -> QuicStreamOrigin.Early(connection::established)
        }

    override fun markReplaySafe() {
        reading.compareAndSet(Reading.Gated, Reading.Open)
    }

    override suspend fun readNext(policy: ReadPolicy): Any {
        when (reading.load()) {
            is Reading.Gated ->
                when (val end = confirmed()) {
                    is EdgeEnd.Open -> markReplaySafe()
                    is EdgeEnd.Ended ->
                        when (end.handshake) {
                            is HandshakeFate.Completed -> markReplaySafe()
                            is HandshakeFate.NotCompleted -> return ended(ScopedRead.End.ConnectionEnded(publicEndOf(end.end)))
                        }
                }
            is Reading.Open, is Reading.AtEnd -> Unit
        }
        return when (val answer = half.take(deadlineOf(policy))) {
            is ReadAnswer.Data -> answer.buffer
            is ReadAnswer.End -> ended(ScopedRead.End.PeerFinished)
            is ReadAnswer.Reset -> ended(ScopedRead.End.PeerReset(answer.code))
            is ReadAnswer.Failed -> noBytes(answer)
            is ReadAnswer.Cancelled -> raiseCancelled(currentCoroutineContext())
        }
    }

    override fun stop(code: QuicAppErrorCode) {
        half.stop(code)
    }

    /** The block that owned this stream returned: a direction not read to its end is stopped. */
    fun complete() {
        when (reading.load()) {
            is Reading.AtEnd -> Unit
            is Reading.Gated, is Reading.Open -> half.stop(codes.leftUnread)
        }
    }

    /** How the connection stands once the handshake an early stream waits for completed, or the connection ended. */
    private suspend fun confirmed(): EdgeEnd {
        connection.state.first { it is QuicConnectionState.Established || it is QuicConnectionState.Closed }
        return connection.end()
    }

    private fun ended(end: ScopedRead.End): ScopedRead.End {
        reading.store(Reading.AtEnd)
        return end
    }

    /** What a failed read is to its caller: a passed deadline or the connection's end as a value, or a misuse, which raises. */
    private fun noBytes(answer: ReadAnswer.Failed): ScopedRead.NoData =
        when (val failure = answer.failure) {
            is OpFailure.DeadlinePassed -> ScopedRead.DeadlinePassed
            is Raised.ConnEnded -> ended(ScopedRead.End.ConnectionEnded(publicEndOf(failure.end)))
            is Raised.PastClose -> ended(ScopedRead.End.ConnectionEnded(publicEndOf(failure.end)))
            is Raised.WriteRefused, is Raised.ReadRefused, is Raised.Thrown, is Raised.Typed, is Raised.StreamClosed,
            is Raised.NoSuchDirection, is Raised.NotNative, is Raised.FactoryNotNative, is Raised.RouteUnresolved,
            is Raised.LoopsClosed, is Raised.StreamsHandled, is Raised.SocketRefused, is Raised.SpecLayoutRefused,
            is Raised.NetworkRefused, is Raised.AccessDenied, is Raised.ChainRefused,
            -> raise(failure, answer.during)
        }
}

/** A [BidiStream] as the public [QuicStream]: its two directions. */
internal class CoreBidiStream(
    override val send: CoreSendStream,
    override val receive: CoreReceiveStream,
) : QuicStream,
    CoreIncoming {
    override val id: QuicStreamId get() = send.id

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
    fun sending(half: SendHalf) = CoreSendStream(half, codes)

    fun both(
        stream: BidiStream,
        arrival: Arrival,
    ) = CoreBidiStream(CoreSendStream(stream.send, codes), CoreReceiveStream(connection, stream.receive, arrival, codes))

    fun incoming(stream: IncomingStream): CoreIncoming =
        when (stream) {
            is IncomingStream.Bidirectional -> both(stream.stream, stream.arrival)
            is IncomingStream.ReceiveOnly -> CoreReceiveStream(connection, stream.receive, stream.arrival, codes)
        }
}
