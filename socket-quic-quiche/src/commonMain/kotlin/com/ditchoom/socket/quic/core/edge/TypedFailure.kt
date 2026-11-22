package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.codec.DEFAULT_MAX_FRAME_BYTES
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicStreamSide

/**
 * How a typed stream frames and refuses frames: the largest frame it accepts or sends, the application error code a
 * stream that carries a malformed frame is reset with, and the codec contexts.
 */
internal class TypedStreamConfig(
    val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    val malformedFrameCode: QuicAppErrorCode = QuicAppErrorCode(MALFORMED_FRAME),
    val decode: DecodeContext = DecodeContext.Empty,
    val encode: EncodeContext = EncodeContext.Empty,
) {
    companion object {
        /** The default application error code of a stream reset for a malformed frame. */
        const val MALFORMED_FRAME = 0x1L
    }
}

/** Why a typed stream refused a frame, or could not be used. */
internal sealed interface TypedFailure {
    /** The codec sized the incoming frame at [declared] bytes; the stream accepts at most [bound]. */
    class Oversized(
        val declared: Int,
        val bound: Int,
    ) : TypedFailure

    /** [bound] bytes are buffered and the codec still cannot size the frame. */
    class Unsized(
        val bound: Int,
    ) : TypedFailure

    /** The codec rejected the bytes of an incoming frame. */
    class Rejected(
        val rejection: Exception,
    ) : TypedFailure

    /** The codec has no frame detection, so no frame can be found in a stream. */
    data object Unframed : TypedFailure

    /** The peer finished the stream with [buffered] bytes of a frame unread. */
    class Truncated(
        val buffered: Int,
    ) : TypedFailure

    /** The value needs more than [bound] bytes to encode. */
    class OversizedValue(
        val bound: Int,
    ) : TypedFailure

    /** The codec failed to encode the value; nothing was sent. */
    class EncodeRejected(
        val rejection: Exception,
    ) : TypedFailure

    /** A [side] operation was started while another on the same side was still running. */
    class Busy(
        val side: QuicStreamSide,
    ) : TypedFailure
}
