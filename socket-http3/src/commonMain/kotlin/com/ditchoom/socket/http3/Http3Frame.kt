package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.ForwardCompatible
import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UnknownVariant
import com.ditchoom.buffer.codec.annotations.UseCodec
import kotlin.jvm.JvmInline

/**
 * An HTTP/3 frame (RFC 9114 §7.1): `Type (i)`, `Length (i)`, `Frame Payload`.
 *
 * This layer models the frame *envelope* only. The payloads of [Data] and
 * [Headers] are kept as opaque [ReadBuffer]s — DATA is request/response body
 * bytes, and a HEADERS payload is a QPACK-encoded field section decoded in a
 * later layer (RFC 9204). [Settings], [GoAway], [MaxPushId] and [CancelPush] are
 * the frames whose payloads are fully structured here. [PushPromise] keeps its
 * promised-request field section opaque (QPACK-encoded), like [Headers].
 *
 * Frame types not modeled explicitly (reserved/GREASE types) decode to [Unknown],
 * which carries the raw type and payload so a receiver can apply RFC 9114 §9's rule
 * of ignoring unknown frame types.
 *
 * The model is **declarative**: `@DispatchOn` + `@FramedBy` + `@ForwardCompatible`
 * generate `Http3FrameCodec` — varint type dispatch, a *computed* varint Length
 * (no `length` field anywhere; the framework derives it from the body on encode
 * and bounds the body with it on decode), and skip-and-preserve for unknown
 * types. Decode is strict per RFC 9114 §7.1: payload bytes past the identified
 * fields are a `DecodeException` (H3_FRAME_ERROR semantics).
 *
 * Note: the [Data]/[Headers]/[PushPromise]/[Unknown] payload is a
 * position-bearing [ReadBuffer] view over the source bytes (see
 * [ReadBufferViewCodec]) — reading it advances its position, so read it once
 * (or `slice()` it for an independent view). Encoding a frame does *not*
 * consume the payload.
 *
 * Each variant's [Http3FrameType] field is its wire discriminator and must be
 * left at its default — it exists because the generated codec re-reads the
 * type as the variant's first field; it is not a user-settable knob.
 */
@ProtocolMessage
@DispatchOn(Http3FrameType::class)
@FramedBy(Http3LengthCodec::class, after = "frameType")
@ForwardCompatible(unknown = Http3Frame.Unknown::class)
sealed interface Http3Frame {
    /** DATA (type 0x00): opaque message-body bytes. */
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class Data(
        val frameType: Http3FrameType = Http3FrameType(Http3FrameType.DATA),
        @RemainingBytes @UseCodec(ReadBufferViewCodec::class) val payload: ReadBuffer,
    ) : Http3Frame {
        constructor(payload: ReadBuffer) : this(Http3FrameType(Http3FrameType.DATA), payload)
    }

    /** HEADERS (type 0x01): a QPACK-encoded field section, opaque at this layer. */
    @PacketType(value = 0x01)
    @ProtocolMessage
    data class Headers(
        val frameType: Http3FrameType = Http3FrameType(Http3FrameType.HEADERS),
        @RemainingBytes @UseCodec(ReadBufferViewCodec::class) val encodedFieldSection: ReadBuffer,
    ) : Http3Frame {
        constructor(encodedFieldSection: ReadBuffer) :
            this(Http3FrameType(Http3FrameType.HEADERS), encodedFieldSection)
    }

    /** SETTINGS (type 0x04): a sequence of identifier/value pairs (RFC 9114 §7.2.4). */
    @PacketType(value = 0x04)
    @ProtocolMessage
    data class Settings(
        val frameType: Http3FrameType = Http3FrameType(Http3FrameType.SETTINGS),
        @RemainingBytes val entries: List<Http3Setting>,
    ) : Http3Frame {
        constructor(entries: List<Http3Setting>) : this(Http3FrameType(Http3FrameType.SETTINGS), entries)
    }

    /**
     * GOAWAY (type 0x07): the sender is shutting down gracefully (RFC 9114 §7.2.6). On a
     * server→client GOAWAY, [id] is the last client-initiated bidirectional stream id the
     * server may process; the client must not open requests with a higher id.
     */
    @PacketType(value = 0x07)
    @ProtocolMessage
    data class GoAway(
        val frameType: Http3FrameType = Http3FrameType(Http3FrameType.GOAWAY),
        @UseCodec(VarIntCodec::class) val id: Long,
    ) : Http3Frame {
        constructor(id: Long) : this(Http3FrameType(Http3FrameType.GOAWAY), id)
    }

    /** MAX_PUSH_ID (type 0x0d): the maximum push id the client will accept (RFC 9114 §7.2.7). */
    @PacketType(value = 0x0d)
    @ProtocolMessage
    data class MaxPushId(
        val frameType: Http3FrameType = Http3FrameType(Http3FrameType.MAX_PUSH_ID),
        @UseCodec(VarIntCodec::class) val pushId: Long,
    ) : Http3Frame {
        constructor(pushId: Long) : this(Http3FrameType(Http3FrameType.MAX_PUSH_ID), pushId)
    }

    /** CANCEL_PUSH (type 0x03): request cancellation of a server push (RFC 9114 §7.2.3). */
    @PacketType(value = 0x03)
    @ProtocolMessage
    data class CancelPush(
        val frameType: Http3FrameType = Http3FrameType(Http3FrameType.CANCEL_PUSH),
        @UseCodec(VarIntCodec::class) val pushId: Long,
    ) : Http3Frame {
        constructor(pushId: Long) : this(Http3FrameType(Http3FrameType.CANCEL_PUSH), pushId)
    }

    /**
     * PUSH_PROMISE (type 0x05, RFC 9114 §7.2.5): the server promises a future server push, naming
     * it by [pushId] and carrying the *promised request*'s QPACK-encoded field section (kept opaque
     * here, like [Headers] — decoded a layer up). Appears interleaved on a client request stream
     * before/among the response frames.
     */
    @PacketType(value = 0x05)
    @ProtocolMessage
    data class PushPromise(
        val frameType: Http3FrameType = Http3FrameType(Http3FrameType.PUSH_PROMISE),
        @UseCodec(VarIntCodec::class) val pushId: Long,
        @RemainingBytes @UseCodec(ReadBufferViewCodec::class) val encodedFieldSection: ReadBuffer,
    ) : Http3Frame {
        constructor(pushId: Long, encodedFieldSection: ReadBuffer) :
            this(Http3FrameType(Http3FrameType.PUSH_PROMISE), pushId, encodedFieldSection)
    }

    /**
     * Any frame whose type is not modeled above — reserved types or GREASE
     * ([Http3FrameType.isReserved]). The `@ForwardCompatible` sink: [type] preserves the full
     * 62-bit type value and [payload] the framed body, re-emitted verbatim on encode (in the
     * type's canonical minimal varint encoding) so receivers can round-trip frames they ignore.
     */
    @UnknownVariant
    data class Unknown(
        val type: Long,
        val payload: ReadBuffer,
    ) : Http3Frame
}

/**
 * The HTTP/3 frame Type varint — the `@DispatchOn` discriminator of [Http3Frame].
 * The companion carries the type codes (RFC 9114 §11.2.1) the rest of the module
 * references as `Http3FrameType.DATA` etc.
 */
@JvmInline
@ProtocolMessage
value class Http3FrameType(
    @UseCodec(VarIntCodec::class) val raw: Long,
) {
    /**
     * Dispatch projection, clamped: a frame type is a 62-bit varint and an
     * out-of-`Int`-range type must NOT alias onto a known small type via
     * truncation (`(2^32).toInt() == 0` would dispatch as DATA). `-1` never
     * matches a `@PacketType`, so oversized types fall through to the
     * [Http3Frame.Unknown] preserve arm.
     */
    @DispatchValue
    val type: Int get() = if (raw in 0..Int.MAX_VALUE.toLong()) raw.toInt() else -1

    companion object {
        const val DATA: Long = 0x00
        const val HEADERS: Long = 0x01
        const val CANCEL_PUSH: Long = 0x03
        const val SETTINGS: Long = 0x04
        const val PUSH_PROMISE: Long = 0x05
        const val GOAWAY: Long = 0x07
        const val MAX_PUSH_ID: Long = 0x0d

        /**
         * Reserved "GREASE" frame types of the form `0x1f * N + 0x21` for N ≥ 0
         * (RFC 9114 §7.2.8). These exist only to exercise the ignore-unknown rule
         * and carry no semantics.
         */
        fun isReserved(type: Long): Boolean = type >= 0x21 && (type - 0x21) % 0x1f == 0L
    }
}

/**
 * One SETTINGS entry: a varint [identifier] and its varint [value].
 *
 * `@ProtocolMessage` generates `Http3SettingCodec` (two QUIC varints via [VarIntCodec]).
 */
@ProtocolMessage
data class Http3Setting(
    @UseCodec(VarIntCodec::class) val identifier: Long,
    @UseCodec(VarIntCodec::class) val value: Long,
)

/** Known SETTINGS identifiers (RFC 9114 §7.2.4.1 / RFC 9204 §5). */
object Http3SettingId {
    const val QPACK_MAX_TABLE_CAPACITY: Long = 0x01
    const val MAX_FIELD_SECTION_SIZE: Long = 0x06
    const val QPACK_BLOCKED_STREAMS: Long = 0x07

    /** RFC 9220 — required by the Extended CONNECT used for WebTransport. */
    const val ENABLE_CONNECT_PROTOCOL: Long = 0x08

    /** RFC 9297 — HTTP Datagrams. Value 1 enables them; required by WebTransport. */
    const val H3_DATAGRAM: Long = 0x33

    /**
     * draft-ietf-webtrans-http3 — the maximum number of concurrent WebTransport sessions the
     * endpoint is willing to accept. A non-zero value (together with [ENABLE_CONNECT_PROTOCOL]
     * and [H3_DATAGRAM]) signals WebTransport support; 0 / absent means the peer accepts none.
     */
    const val WEBTRANSPORT_MAX_SESSIONS: Long = 0xc671706a

    /**
     * Legacy WebTransport toggle from draft-02 (`SETTINGS_ENABLE_WEBTRANSPORT`). Superseded by
     * [WEBTRANSPORT_MAX_SESSIONS] but advertised alongside it for interop with older peers.
     */
    const val ENABLE_WEBTRANSPORT: Long = 0x2b603742
}

/**
 * Unidirectional stream-type prefixes (RFC 9114 §6.2 / §11.2.1): the first varint on a
 * uni stream identifies its role. A client opens [CONTROL] + the two QPACK streams; the
 * server opens its own set. Unknown types are reserved/GREASE and ignored.
 */
object Http3StreamType {
    const val CONTROL: Long = 0x00
    const val PUSH: Long = 0x01
    const val QPACK_ENCODER: Long = 0x02
    const val QPACK_DECODER: Long = 0x03
}
