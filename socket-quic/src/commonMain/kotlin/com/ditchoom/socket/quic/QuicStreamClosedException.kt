package com.ditchoom.socket.quic

/** Which half of a stream an operation needed. */
sealed interface QuicStreamSide {
    /** The receiving half. */
    data object Read : QuicStreamSide

    /** The sending half. */
    data object Write : QuicStreamSide
}

/**
 * Thrown when [side] of stream [streamId] was used after this endpoint closed it: a read after the stream was
 * closed, a write after it was closed or its send side finished.
 *
 * A local usage error scoped to one stream, so it is neither a [QuicCloseException] nor a [QuicStreamException].
 */
class QuicStreamClosedException(
    val streamId: Long,
    val side: QuicStreamSide,
) : IllegalStateException("QUIC stream $streamId: $side side is closed")
