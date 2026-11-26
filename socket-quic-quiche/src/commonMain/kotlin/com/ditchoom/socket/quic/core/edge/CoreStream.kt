package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamOrigin
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.core.stream.StreamKind
import com.ditchoom.socket.quic.core.stream.kindOf
import kotlin.time.Duration

/** A bidirectional stream of a [CoreConnection] as the public [ByteStream] contract sees it: both of its halves. */
internal class CoreStream(
    private val stream: BidiStream,
) : ByteStream,
    HalfCloseable,
    Resettable {
    override val readPolicy: ReadPolicy get() = stream.receive.readPolicy
    override val writePolicy: WritePolicy get() = stream.send.writePolicy

    /** Open until the caller closes or resets it; our FIN alone leaves it open for reads. */
    override val isOpen: Boolean get() = stream.receive.isOpen()

    override suspend fun read(deadline: Duration): ReadResult = stream.receive.read(deadline)

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = stream.send.write(buffer, deadline)

    override suspend fun shutdownSend() = stream.send.finish()

    override suspend fun close() {
        try {
            stream.send.finish()
        } finally {
            stream.receive.close()
        }
    }

    /** Resets both directions with [errorCode]: STOP_SENDING for the peer's, RESET_STREAM for ours; once only. */
    override suspend fun reset(errorCode: Long) {
        val code = QuicAppErrorCode(errorCode)
        when (stream.receive.stop(code)) {
            Stopping.Now -> stream.send.reset(code)
            Stopping.Already -> Unit
        }
    }
}

/**
 * A send-only stream as the public [ByteStream] contract sees it, which has a read: a read is refused as a direction the
 * stream does not have. The core's own [SendHalf] has no read.
 */
internal class CoreSendOnlyStream(
    private val send: SendHalf,
    override val readPolicy: ReadPolicy,
) : ByteStream,
    HalfCloseable,
    Resettable {
    override val writePolicy: WritePolicy get() = send.writePolicy
    override val isOpen: Boolean get() = send.isOpen()

    override suspend fun read(deadline: Duration): ReadResult = absent(send.id, QuicStreamSide.Read)

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = send.write(buffer, deadline)

    override suspend fun shutdownSend() = send.finish()

    override suspend fun close() = send.finish()

    override suspend fun reset(errorCode: Long) = send.reset(QuicAppErrorCode(errorCode))
}

/**
 * A connection's streams in the core's own shapes: an open of a bidirectional stream gives both halves, an open of a
 * unidirectional one only a [SendHalf], and a stream the peer opened is [IncomingStream] by its kind. [public] wraps the
 * two a 0-RTT block opens as the [QuicByteStream] its [com.ditchoom.socket.quic.QuicEarlyDataScope] hands out.
 */
internal class StreamHalves(
    private val connection: CoreConnection,
    private val edge: EdgeState,
    private val readPolicy: ReadPolicy,
    private val writePolicy: WritePolicy,
) {
    suspend fun openBidirectional(): BidiStream = both(connection.open(StreamKind.Bidirectional))

    suspend fun openSendOnly(): SendHalf = SendHalf(connection, connection.open(StreamKind.Unidirectional), writePolicy)

    /** Both halves of the bidirectional stream [id], which this side opened. */
    fun openedBoth(id: QuicStreamId): BidiStream = both(id)

    /** The sending half of the unidirectional stream [id], which this side opened. */
    fun openedSendOnly(id: QuicStreamId): SendHalf = SendHalf(connection, id, writePolicy)

    /** The next stream the peer opened, of either kind. */
    suspend fun accept(): IncomingStream = incomingOf(edge.incoming.receiveAny())

    /** The next bidirectional stream the peer opened; a unidirectional one is left for [acceptReceiveOnly]. */
    suspend fun acceptBidirectional(): IncomingStream.Bidirectional = bidirectional(edge.incoming.bidirectional.receive())

    /** The next stream the peer opened to send only; a bidirectional one is left for [acceptBidirectional]. */
    suspend fun acceptReceiveOnly(): IncomingStream.ReceiveOnly = receiveOnly(edge.incoming.unidirectional.receive())

    fun incomingOf(peer: PeerStream): IncomingStream =
        when (kindOf(QuicStreamId(peer.id))) {
            StreamKind.Bidirectional -> bidirectional(peer)
            StreamKind.Unidirectional -> receiveOnly(peer)
        }

    private fun bidirectional(peer: PeerStream) = IncomingStream.Bidirectional(both(QuicStreamId(peer.id)), peer.arrival)

    private fun receiveOnly(peer: PeerStream) =
        IncomingStream.ReceiveOnly(ReceiveHalf(connection, QuicStreamId(peer.id), readPolicy), peer.arrival)

    fun public(stream: BidiStream): QuicByteStream = QuicByteStream(stream.id, CoreStream(stream), QuicStreamOrigin.Confirmed)

    fun public(send: SendHalf): QuicByteStream = QuicByteStream(send.id, CoreSendOnlyStream(send, readPolicy), QuicStreamOrigin.Confirmed)

    private fun both(id: QuicStreamId) = BidiStream(SendHalf(connection, id, writePolicy), ReceiveHalf(connection, id, readPolicy))
}

/** A call on the direction [side] of a unidirectional stream that has no such direction. */
private fun absent(
    id: QuicStreamId,
    side: QuicStreamSide,
): Nothing = raise(Raised.NoSuchDirection(id, side), QuicCloseCircumstance.Ended)
