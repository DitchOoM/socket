package com.ditchoom.socket.http3

/**
 * A typed HTTP/3 (RFC 9114 §8.1) / QPACK (RFC 9204 §8.3) protocol violation. This is the reason an
 * [Http3StreamException] is raised — every distinct violation is its own variant carrying its structured
 * context (push ids, setting/frame type codes, an underlying buffer cause, …) rather than a hand-written
 * message string. The RFC application [errorCode] an endpoint puts on the wire is *derived* from the
 * variant, and [describe] renders the human-readable diagnostic from the typed fields. Errors stay typed,
 * never stringly — mirroring the `QuicError` sealed convention in `:socket-quic`.
 */
sealed interface Http3Violation {
    /** The RFC 9114 §8.1 / RFC 9204 §8.3 application error code for a CONNECTION_CLOSE or stream reset. */
    val errorCode: Long

    /** A human-readable diagnostic built from this violation's typed fields (used as the exception message). */
    fun describe(): String

    // --- Frame layer (RFC 9114 §7.1) — H3_FRAME_ERROR ---------------------------------------------

    /** A frame could not be decoded (truncated/oversized payload, bad varint, non-UTF-8 octet, …). */
    data class MalformedFrame(
        val cause: Throwable? = null,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_ERROR

        override fun describe() = "malformed HTTP/3 frame" + (cause?.message?.let { ": $it" } ?: "")
    }

    /** A frame whose total size (type + length + body) exceeds the Int-addressable range (§7.1). */
    data object FrameSizeExceedsAddressableRange : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_ERROR

        override fun describe() = "HTTP/3 frame size exceeds the addressable range"
    }

    /** The stream ended mid-frame, leaving [trailingBytes] that don't form a whole frame. */
    data class FrameTruncatedAtEnd(
        val trailingBytes: Int,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_ERROR

        override fun describe() = "stream ended mid-frame: $trailingBytes trailing byte(s)"
    }

    /** The stream ended before a complete QUIC varint (a uni stream-type prefix or WT signal). */
    data object VarIntTruncatedAtEnd : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_ERROR

        override fun describe() = "stream ended before a complete varint"
    }

    // --- Stream reset — H3_GENERAL_PROTOCOL_ERROR -------------------------------------------------

    /** The peer reset the stream while it was being reassembled. */
    data object StreamResetByPeer : Http3Violation {
        override val errorCode get() = Http3ErrorCode.GENERAL_PROTOCOL_ERROR

        override fun describe() = "stream was reset by the peer"
    }

    // --- Invalid frame in context (RFC 9114 §4.1 / §7.1 / §7.2) — H3_FRAME_UNEXPECTED -------------

    /** A reserved HTTP/2 frame type (§11.2.1) — PRIORITY/PING/WINDOW_UPDATE/CONTINUATION — was received. */
    data class ReservedHttp2Frame(
        val type: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_UNEXPECTED

        override fun describe() = "reserved HTTP/2 frame type 0x${type.toString(16)} received"
    }

    /** A second SETTINGS frame on the control stream (SETTINGS may appear once, §7.2.4). */
    data object SecondSettingsFrame : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_UNEXPECTED

        override fun describe() = "a second SETTINGS frame on the control stream"
    }

    /** A server sent MAX_PUSH_ID, which is client→server only (§7.2.7). */
    data object MaxPushIdFromServer : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_UNEXPECTED

        override fun describe() = "MAX_PUSH_ID received from the server"
    }

    /** A PUSH_PROMISE on the control stream — it is a request-stream frame (§7.2.5). */
    data object PushPromiseOnControlStream : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_UNEXPECTED

        override fun describe() = "PUSH_PROMISE on the control stream"
    }

    /** A frame of type [frameType] appeared where it is not permitted ([context]). */
    data class UnexpectedFrame(
        val frameType: Long,
        val context: Http3FrameContext,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.FRAME_UNEXPECTED

        override fun describe() = "unexpected frame type 0x${frameType.toString(16)} ${context.label}"
    }

    // --- Incomplete message (RFC 9114 §4.1) — H3_REQUEST_INCOMPLETE -------------------------------

    /** A request/response/CONNECT stream ended before its HEADERS frame ([context]). */
    data class StreamEndedBeforeHeaders(
        val context: Http3FrameContext,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.REQUEST_INCOMPLETE

        override fun describe() = "stream ended ${context.label}"
    }

    // --- Malformed message (RFC 9114 §4.1.2 / §4.3) — H3_MESSAGE_ERROR ----------------------------

    /** A response field section had no `:status` pseudo-header. */
    data object StatusPseudoHeaderMissing : Http3Violation {
        override val errorCode get() = Http3ErrorCode.MESSAGE_ERROR

        override fun describe() = "response HEADERS missing the :status pseudo-header"
    }

    /** The `:status` pseudo-header value [raw] was not a number. */
    data class StatusNotNumeric(
        val raw: String,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.MESSAGE_ERROR

        override fun describe() = "response :status was not a number: \"$raw\""
    }

    /** A required pseudo-header [name] was absent ([inPushPromise] distinguishes request vs PUSH_PROMISE). */
    data class MissingPseudoHeader(
        val name: String,
        val inPushPromise: Boolean,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.MESSAGE_ERROR

        override fun describe() = "${if (inPushPromise) "PUSH_PROMISE" else "request HEADERS"} missing the $name pseudo-header"
    }

    // --- SETTINGS (RFC 9114 §7.2.4.1) — H3_SETTINGS_ERROR -----------------------------------------

    /** A reserved HTTP/2 setting identifier (§11.2.2) was received. */
    data class ReservedHttp2Setting(
        val identifier: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.SETTINGS_ERROR

        override fun describe() = "reserved HTTP/2 setting identifier 0x${identifier.toString(16)} in SETTINGS"
    }

    /** A setting identifier appeared more than once in the SETTINGS frame. */
    data class DuplicateSetting(
        val identifier: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.SETTINGS_ERROR

        override fun describe() = "duplicate setting identifier 0x${identifier.toString(16)} in SETTINGS"
    }

    // --- Control stream (RFC 9114 §6.2.1) — H3_MISSING_SETTINGS / H3_CLOSED_CRITICAL_STREAM -------

    /** The control stream's first frame (type [frameType]) was not SETTINGS. */
    data class FirstControlFrameNotSettings(
        val frameType: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.MISSING_SETTINGS

        override fun describe() = "control stream's first frame was type 0x${frameType.toString(16)}, expected SETTINGS"
    }

    /** The peer's control stream ended before delivering SETTINGS (a critical stream closed). */
    data object ControlStreamEndedBeforeSettings : Http3Violation {
        override val errorCode get() = Http3ErrorCode.CLOSED_CRITICAL_STREAM

        override fun describe() = "peer control stream ended before SETTINGS"
    }

    /** A QPACK encoder/decoder stream (a critical stream) was reset by the peer (RFC 9204 §4.2). */
    data object QpackStreamReset : Http3Violation {
        override val errorCode get() = Http3ErrorCode.CLOSED_CRITICAL_STREAM

        override fun describe() = "QPACK stream was reset by the peer"
    }

    // --- Push (RFC 9114 §4.6 / §7.2.7) — H3_ID_ERROR / H3_REQUEST_CANCELLED -----------------------

    /** A server push arrived though MAX_PUSH_ID was never sent (push disabled). */
    data object PushDisabled : Http3Violation {
        override val errorCode get() = Http3ErrorCode.ID_ERROR

        override fun describe() = "received a push but MAX_PUSH_ID was never sent (push disabled)"
    }

    /** A push id exceeded the current maximum the client advertised. */
    data class PushIdExceedsMax(
        val pushId: Long,
        val max: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.ID_ERROR

        override fun describe() = "push id $pushId exceeds the current maximum ($max)"
    }

    /** The client cancelled a server push. */
    data class PushCancelledByClient(
        val pushId: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.REQUEST_CANCELLED

        override fun describe() = "server push $pushId cancelled by the client"
    }

    /** The server withdrew a push it had promised (CANCEL_PUSH). */
    data class PushCancelledByServer(
        val pushId: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.REQUEST_CANCELLED

        override fun describe() = "server cancelled push $pushId"
    }

    // --- QPACK field section (RFC 9204 §2.2) — QPACK_DECOMPRESSION_FAILED --------------------------

    /** A QPACK encoded field section could not be decoded. */
    data class QpackDecompressionFailed(
        val cause: Throwable? = null,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.QPACK_DECOMPRESSION_FAILED

        override fun describe() = "QPACK field-section decode failed" + (cause?.message?.let { ": $it" } ?: "")
    }

    // --- QPACK encoder stream (RFC 9204 §4.3) — QPACK_ENCODER_STREAM_ERROR -------------------------

    /** A Set Dynamic Table Capacity instruction exceeded the advertised maximum. */
    data class QpackSetCapacityExceedsMax(
        val requested: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR

        override fun describe() = "Set Dynamic Table Capacity $requested exceeds the advertised maximum"
    }

    /** An encoder-stream relative index referenced a missing (evicted/never-inserted) entry. */
    data class QpackEncoderRelativeIndexMissing(
        val relativeIndex: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR

        override fun describe() = "encoder-stream relative index $relativeIndex references a missing entry"
    }

    /** An encoder-stream instruction named a static-table index out of range. */
    data class QpackStaticNameIndexOutOfRange(
        val index: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR

        override fun describe() = "static name index $index out of range"
    }

    /** An inserted entry's size exceeded the dynamic table capacity. */
    data class QpackInsertExceedsCapacity(
        val entrySize: Long,
        val capacity: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR

        override fun describe() = "inserted entry (size $entrySize) exceeds table capacity $capacity"
    }

    // --- QPACK decoder stream (RFC 9204 §4.4) — QPACK_DECODER_STREAM_ERROR -------------------------

    /** An Insert Count Increment pushed the Known Received Count past the number of inserts. */
    data class QpackInsertCountIncrementPastInserts(
        val increment: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.QPACK_DECODER_STREAM_ERROR

        override fun describe() = "Insert Count Increment $increment pushes Known Received Count past inserts"
    }

    /** A Section Acknowledgment named a stream with no outstanding section. */
    data class QpackSectionAckWithoutOutstanding(
        val streamId: Long,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.QPACK_DECODER_STREAM_ERROR

        override fun describe() = "Section Acknowledgment for stream $streamId with no outstanding section"
    }

    // --- QPACK instruction stream (direction-dependent) ------------------------------------------

    /** A malformed instruction body on a QPACK [stream] (RFC 9204 §4.2). */
    data class MalformedQpackInstruction(
        val stream: QpackStream,
        val cause: Throwable? = null,
    ) : Http3Violation {
        override val errorCode get() = stream.streamErrorCode

        override fun describe() = "malformed QPACK ${stream.label} instruction" + (cause?.message?.let { ": $it" } ?: "")
    }

    /** A QPACK [stream] ended in the middle of an instruction. */
    data class QpackInstructionTruncated(
        val stream: QpackStream,
    ) : Http3Violation {
        override val errorCode get() = stream.streamErrorCode

        override fun describe() = "QPACK ${stream.label} stream ended mid-instruction"
    }

    // --- Connection / WebTransport — H3_GENERAL_PROTOCOL_ERROR ------------------------------------

    /** The connection closed before the peer's SETTINGS were received. */
    data object ConnectionClosedBeforeSettings : Http3Violation {
        override val errorCode get() = Http3ErrorCode.GENERAL_PROTOCOL_ERROR

        override fun describe() = "connection closed before the peer's SETTINGS were received"
    }

    /** A non-typed error surfaced while reading the control stream. */
    data class ControlStreamError(
        val cause: Throwable? = null,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.GENERAL_PROTOCOL_ERROR

        override fun describe() = "control stream error" + (cause?.message?.let { ": $it" } ?: "")
    }

    /** A non-typed error surfaced while reading a server push stream. */
    data class PushStreamError(
        val cause: Throwable? = null,
    ) : Http3Violation {
        override val errorCode get() = Http3ErrorCode.GENERAL_PROTOCOL_ERROR

        override fun describe() = "push stream error" + (cause?.message?.let { ": $it" } ?: "")
    }

    /** A WebTransport CLOSE_SESSION capsule was shorter than its 4-byte error code (draft §5). */
    data object WebTransportCloseCapsuleTooShort : Http3Violation {
        override val errorCode get() = Http3ErrorCode.GENERAL_PROTOCOL_ERROR

        override fun describe() = "WT_CLOSE_SESSION capsule shorter than its 4-byte error code"
    }
}

/**
 * Where a frame appeared — the context for [Http3Violation.UnexpectedFrame] and
 * [Http3Violation.StreamEndedBeforeHeaders]. The [label] is a trailing clause that reads naturally after
 * both "unexpected frame type 0xNN …" and "stream ended …".
 */
enum class Http3FrameContext(
    val label: String,
) {
    CONTROL_STREAM("on the control stream"),
    BEFORE_RESPONSE_HEADERS("before the response HEADERS"),
    BEFORE_CONNECT_RESPONSE_HEADERS("before the CONNECT response HEADERS"),
    BEFORE_REQUEST_HEADERS("before the request HEADERS"),
    RESPONSE_BODY("in the response body"),
    REQUEST_HEAD("as the request's first frame, expected HEADERS"),
    REQUEST_BODY("in the request body"),
    PUSH_STREAM("on a push stream"),
}

/** A QPACK unidirectional instruction stream (RFC 9204 §4.2) and its critical-stream error code. */
enum class QpackStream(
    val label: String,
    val streamErrorCode: Long,
) {
    ENCODER("encoder", Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR),
    DECODER("decoder", Http3ErrorCode.QPACK_DECODER_STREAM_ERROR),
}

/** The wire frame-type code of any [Http3Frame] (the modeled variant's type, or [Http3Frame.Unknown.type]). */
internal val Http3Frame.wireType: Long
    get() =
        when (this) {
            is Http3Frame.Data -> Http3FrameType.DATA
            is Http3Frame.Headers -> Http3FrameType.HEADERS
            is Http3Frame.Settings -> Http3FrameType.SETTINGS
            is Http3Frame.GoAway -> Http3FrameType.GOAWAY
            is Http3Frame.MaxPushId -> Http3FrameType.MAX_PUSH_ID
            is Http3Frame.CancelPush -> Http3FrameType.CANCEL_PUSH
            is Http3Frame.PushPromise -> Http3FrameType.PUSH_PROMISE
            is Http3Frame.Unknown -> type
        }

/**
 * Raised when an HTTP/3 stream can't be processed — a frame that can't be reassembled (truncated at FIN,
 * peer reset) or a protocol violation detected while routing/reading frames. The [violation] is the typed
 * reason (see [Http3Violation]); [errorCode] is the RFC 9114 §8.1 / RFC 9204 §8.3 code the endpoint would
 * use to abort the connection or stream, derived from the violation. [cause] preserves an underlying
 * buffer/codec failure where one wrapped into the violation.
 */
class Http3StreamException(
    val violation: Http3Violation,
) : Exception(violation.describe(), violation.causeOrNull()) {
    val errorCode: Long get() = violation.errorCode
}

/** The underlying buffer/codec failure a cause-bearing violation wrapped, chained into the exception. */
private fun Http3Violation.causeOrNull(): Throwable? =
    when (this) {
        is Http3Violation.MalformedFrame -> cause
        is Http3Violation.QpackDecompressionFailed -> cause
        is Http3Violation.MalformedQpackInstruction -> cause
        is Http3Violation.ControlStreamError -> cause
        is Http3Violation.PushStreamError -> cause
        else -> null
    }
