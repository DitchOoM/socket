package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.HandshakeRecord
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamOrigin
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.core.conn.Arrival
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration

/*
 * A white-box test reads and writes a core connection's streams through the core's own halves. These calls wrap those
 * halves as a [ByteStream] so a test can drive a stream with the byte-stream verbs.
 */

/** Opens the next bidirectional stream, as both of its halves behind a [QuicByteStream]. */
internal suspend fun CoreConnection.openStream(): QuicByteStream = byteStream(halves.openBidirectional())

/** Opens the next unidirectional stream, as its send half behind a [QuicByteStream]. */
internal suspend fun CoreConnection.openUniStream(): QuicByteStream = byteStream(halves.openSendOnly())

/** Accepts the next stream the peer opened, of either kind. */
internal suspend fun CoreConnection.acceptStream(): QuicByteStream = byteStream(halves.accept())

/** Every stream the peer opens, as a [QuicByteStream] each. */
internal fun CoreConnection.streams(): Flow<QuicByteStream> = edge.incoming.all().map { byteStream(halves.incomingOf(it)) }

/** The ALPN the handshake settled on, which an established connection has. */
internal val CoreConnection.negotiatedAlpn: String
    get() =
        when (val settled = edge.handshake.value) {
            is HandshakeRecord.Settled -> settled.negotiatedAlpn
            HandshakeRecord.Pending -> error("the handshake has not completed")
        }

/** [handleStreams] handing each stream to [handler] as a [QuicByteStream]. */
internal suspend fun CoreConnection.handleStreams(
    resetCode: HandlerResetCode = HandlerResetCode.Default,
    handler: suspend (QuicByteStream) -> Unit,
): StreamsHandled = handleStreams(resetCode, ::byteStream, handler)

internal fun CoreConnection.byteStream(stream: BidiStream): QuicByteStream = halves.public(stream)

internal fun CoreConnection.byteStream(send: SendHalf): QuicByteStream = halves.public(send)

/** A stream that arrived in 0-RTT is read only once the handshake completes, unless it is marked replay-safe. */
internal fun CoreConnection.byteStream(incoming: IncomingStream): QuicByteStream =
    when (incoming) {
        is IncomingStream.Bidirectional -> QuicByteStream(incoming.stream.id, CoreStream(incoming.stream), originOf(incoming.arrival))
        is IncomingStream.ReceiveOnly ->
            QuicByteStream(incoming.receive.id, ReceiveOnlyByteStream(incoming.receive, writePolicy), originOf(incoming.arrival))
    }

private fun CoreConnection.originOf(arrival: Arrival): QuicStreamOrigin =
    when (arrival) {
        Arrival.Confirmed -> QuicStreamOrigin.Confirmed
        Arrival.Early -> QuicStreamOrigin.Early(::established)
    }

/** A stream the peer opened to send only, whose write is refused as a direction it does not have. */
private class ReceiveOnlyByteStream(
    private val receive: ReceiveHalf,
    override val writePolicy: WritePolicy,
) : ByteStream,
    HalfCloseable,
    Resettable {
    override val readPolicy: ReadPolicy get() = receive.readPolicy
    override val isOpen: Boolean get() = receive.isOpen()

    override suspend fun read(deadline: Duration): ReadResult = receive.read(deadline)

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = absent(receive.id, QuicStreamSide.Write)

    override suspend fun shutdownSend(): Unit = absent(receive.id, QuicStreamSide.Write)

    override suspend fun close() = receive.close()

    override suspend fun reset(errorCode: Long) {
        receive.stop(QuicAppErrorCode(errorCode))
    }
}

private fun absent(
    id: QuicStreamId,
    side: QuicStreamSide,
): Nothing = raise(Raised.NoSuchDirection(id, side), QuicCloseCircumstance.Ended)
