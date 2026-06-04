package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import kotlin.jvm.JvmInline

/**
 * An HTTP/3 frame (RFC 9114 §7.1): `Type (i)`, `Length (i)`, `Frame Payload`.
 *
 * This layer models the frame *envelope* only. The payloads of [Data] and
 * [Headers] are kept as opaque [ReadBuffer]s — DATA is request/response body
 * bytes, and a HEADERS payload is a QPACK-encoded field section decoded in a
 * later layer (RFC 9204). [Settings], [GoAway], [MaxPushId] and [CancelPush] are
 * the frames whose payloads are fully structured here.
 *
 * Frame types not modeled explicitly (PUSH_PROMISE and reserved/GREASE types)
 * decode to [Unknown], which carries the raw type and payload so a receiver can
 * apply RFC 9114 §9's rule of ignoring unknown frame types.
 *
 * Note: the [Data]/[Headers]/[Unknown] payload is a position-bearing
 * [ReadBuffer] view over the source bytes — reading it advances its position,
 * so read it once (or `slice()` it for an independent view). Encoding a frame
 * via [Http3FrameCodec] does *not* consume the payload.
 *
 * Single-field variants are `@JvmInline value class` for consistency with the rest of the
 * codebase (e.g. [com.ditchoom.socket.quic.DatagramReceiveResult], `QuicStreamId`); note they
 * box when handled through the [Http3Frame] supertype, so this is for descriptiveness, not a
 * zero-allocation guarantee. [Unknown] carries two fields and stays a `data class`.
 */
sealed interface Http3Frame {
    /** DATA (type 0x00): opaque message-body bytes. */
    @JvmInline
    value class Data(
        val payload: ReadBuffer,
    ) : Http3Frame

    /** HEADERS (type 0x01): a QPACK-encoded field section, opaque at this layer. */
    @JvmInline
    value class Headers(
        val encodedFieldSection: ReadBuffer,
    ) : Http3Frame

    /** SETTINGS (type 0x04): a sequence of identifier/value pairs (RFC 9114 §7.2.4). */
    @JvmInline
    value class Settings(
        val entries: List<Http3Setting>,
    ) : Http3Frame

    /**
     * GOAWAY (type 0x07): the sender is shutting down gracefully (RFC 9114 §7.2.6). On a
     * server→client GOAWAY, [id] is the last client-initiated bidirectional stream id the
     * server may process; the client must not open requests with a higher id.
     */
    @JvmInline
    value class GoAway(
        val id: Long,
    ) : Http3Frame

    /** MAX_PUSH_ID (type 0x0d): the maximum push id the client will accept (RFC 9114 §7.2.7). */
    @JvmInline
    value class MaxPushId(
        val pushId: Long,
    ) : Http3Frame

    /** CANCEL_PUSH (type 0x03): request cancellation of a server push (RFC 9114 §7.2.3). */
    @JvmInline
    value class CancelPush(
        val pushId: Long,
    ) : Http3Frame

    /**
     * Any frame whose type is not modeled above — PUSH_PROMISE, reserved types, or GREASE
     * ([Http3FrameType.isReserved]). The [payload] is retained for round-tripping; receivers
     * normally ignore it. Two fields, so it stays a `data class`.
     */
    data class Unknown(
        val type: Long,
        val payload: ReadBuffer,
    ) : Http3Frame
}

/** One SETTINGS entry: a varint [identifier] and its varint [value]. */
data class Http3Setting(
    val identifier: Long,
    val value: Long,
)

/** HTTP/3 frame type codes (RFC 9114 §11.2.1). */
object Http3FrameType {
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

/** Known SETTINGS identifiers (RFC 9114 §7.2.4.1 / RFC 9204 §5). */
object Http3SettingId {
    const val QPACK_MAX_TABLE_CAPACITY: Long = 0x01
    const val MAX_FIELD_SECTION_SIZE: Long = 0x06
    const val QPACK_BLOCKED_STREAMS: Long = 0x07

    /** RFC 9220 — required by the Extended CONNECT used for WebTransport. */
    const val ENABLE_CONNECT_PROTOCOL: Long = 0x08
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
