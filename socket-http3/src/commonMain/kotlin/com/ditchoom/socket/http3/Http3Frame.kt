package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer

/**
 * HTTP/3 frame type codes (RFC 9114 §7.2, §11.2.1). Each is a QUIC varint on the wire.
 * Reserved/greasing types (`0x1f * N + 0x21`) and any other unrecognised type decode to
 * [Http3Frame.Unknown] and must be skipped (RFC 9114 §9).
 */
object Http3FrameType {
    const val DATA: Long = 0x00
    const val HEADERS: Long = 0x01
    const val CANCEL_PUSH: Long = 0x03
    const val SETTINGS: Long = 0x04
    const val PUSH_PROMISE: Long = 0x05
    const val GOAWAY: Long = 0x07
    const val MAX_PUSH_ID: Long = 0x0d
}

/**
 * A decoded HTTP/3 frame (RFC 9114 §7.2). Wire layout is always
 * `Type(varint) Length(varint) Payload[Length]`; [Http3FrameCodec] reads the varint Type
 * and dispatches here (the buffer-codec annotation framework can't model a varint
 * discriminator, so dispatch is hand-written — payload *structs* like [Http3SettingsPair]
 * stay annotated).
 *
 * **Buffer lifetime:** the byte-carrying variants ([Data], [Headers], [Unknown]) hold a
 * zero-copy [ReadBuffer] view over the source buffer. The view is valid only for the scope
 * in which the source buffer is live; callers that must retain bytes beyond that scope copy
 * them out. (A later owned-slice form can swap in once the stream layer manages lifetimes.)
 */
sealed interface Http3Frame {
    /** The frame's wire type code. */
    val type: Long

    /** DATA (0x0): opaque request/response body bytes. */
    data class Data(
        val payload: ReadBuffer,
    ) : Http3Frame {
        override val type: Long get() = Http3FrameType.DATA
    }

    /** HEADERS (0x1): a QPACK-encoded field-block (opaque here; QPACK decode lands later). */
    data class Headers(
        val encodedFieldBlock: ReadBuffer,
    ) : Http3Frame {
        override val type: Long get() = Http3FrameType.HEADERS
    }

    /** SETTINGS (0x4): an identifier/value parameter list (RFC 9114 §7.2.4). */
    data class Settings(
        val parameters: List<Http3SettingsPair>,
    ) : Http3Frame {
        override val type: Long get() = Http3FrameType.SETTINGS
    }

    /** GOAWAY (0x7): the last client-initiated stream id (or push id) the sender will process. */
    data class GoAway(
        val id: Long,
    ) : Http3Frame {
        override val type: Long get() = Http3FrameType.GOAWAY
    }

    /** CANCEL_PUSH (0x3): request cancellation of a server push. */
    data class CancelPush(
        val pushId: Long,
    ) : Http3Frame {
        override val type: Long get() = Http3FrameType.CANCEL_PUSH
    }

    /** MAX_PUSH_ID (0xd): the maximum push id the client will accept. */
    data class MaxPushId(
        val pushId: Long,
    ) : Http3Frame {
        override val type: Long get() = Http3FrameType.MAX_PUSH_ID
    }

    /**
     * A frame whose [type] this implementation does not model (extensions, greasing,
     * or future frames). The raw [payload] bytes are preserved so the frame can be
     * skipped (RFC 9114 §9) or forwarded without loss.
     */
    data class Unknown(
        override val type: Long,
        val payload: ReadBuffer,
    ) : Http3Frame
}
