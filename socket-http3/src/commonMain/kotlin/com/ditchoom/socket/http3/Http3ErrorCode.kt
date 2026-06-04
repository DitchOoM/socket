package com.ditchoom.socket.http3

/**
 * HTTP/3 and QPACK error codes (RFC 9114 §8.1 + RFC 9204 §8.3).
 *
 * These are the application error codes an HTTP/3 endpoint puts on the wire when it aborts a
 * connection (a QUIC CONNECTION_CLOSE of type `0x1d`) or a stream (RESET_STREAM / STOP_SENDING)
 * because the peer violated the protocol. They live in the `0x0100`–`0x0110` (HTTP/3) and
 * `0x0200`–`0x0202` (QPACK) ranges so they never collide with QUIC transport codes (RFC 9000 §20).
 *
 * Naming follows the codebase convention (bare constants, no `H3_` prefix — cf. [Http3FrameType],
 * [Http3SettingId], [Http3StreamType]); the RFC's `H3_…` / `QPACK_…` spelling is given per entry.
 */
object Http3ErrorCode {
    /** `H3_NO_ERROR` — graceful close, no error (RFC 9114 §8.1). */
    const val NO_ERROR: Long = 0x0100

    /** `H3_GENERAL_PROTOCOL_ERROR` — a protocol violation with no more specific code. */
    const val GENERAL_PROTOCOL_ERROR: Long = 0x0101

    /** `H3_INTERNAL_ERROR` — an internal error in the HTTP/3 stack. */
    const val INTERNAL_ERROR: Long = 0x0102

    /** `H3_STREAM_CREATION_ERROR` — the peer created a stream it is not allowed to. */
    const val STREAM_CREATION_ERROR: Long = 0x0103

    /** `H3_CLOSED_CRITICAL_STREAM` — a control or QPACK stream (a "critical" stream) was closed. */
    const val CLOSED_CRITICAL_STREAM: Long = 0x0104

    /** `H3_FRAME_UNEXPECTED` — a frame was received on a stream where it is not permitted. */
    const val FRAME_UNEXPECTED: Long = 0x0105

    /** `H3_FRAME_ERROR` — a frame was malformed (bad length, truncated payload). */
    const val FRAME_ERROR: Long = 0x0106

    /** `H3_EXCESSIVE_LOAD` — the peer's behavior is imposing excessive load. */
    const val EXCESSIVE_LOAD: Long = 0x0107

    /** `H3_ID_ERROR` — a stream id or push id was used incorrectly (e.g. exceeds a limit). */
    const val ID_ERROR: Long = 0x0108

    /** `H3_SETTINGS_ERROR` — the SETTINGS frame contained an invalid or duplicate setting. */
    const val SETTINGS_ERROR: Long = 0x0109

    /** `H3_MISSING_SETTINGS` — the control stream's first frame was not SETTINGS. */
    const val MISSING_SETTINGS: Long = 0x010a

    /** `H3_REQUEST_REJECTED` — a request was rejected before any application processing. */
    const val REQUEST_REJECTED: Long = 0x010b

    /** `H3_REQUEST_CANCELLED` — a request or its response was cancelled. */
    const val REQUEST_CANCELLED: Long = 0x010c

    /** `H3_REQUEST_INCOMPLETE` — a request stream terminated before the message was complete. */
    const val REQUEST_INCOMPLETE: Long = 0x010d

    /** `H3_MESSAGE_ERROR` — a malformed request/response message (bad/missing pseudo-headers, etc.). */
    const val MESSAGE_ERROR: Long = 0x010e

    /** `H3_CONNECT_ERROR` — the TCP-like connection established for a CONNECT request was reset/errored. */
    const val CONNECT_ERROR: Long = 0x010f

    /** `H3_VERSION_FALLBACK` — the requested operation cannot be served over HTTP/3; retry over HTTP/1.1. */
    const val VERSION_FALLBACK: Long = 0x0110

    /** `QPACK_DECOMPRESSION_FAILED` — a field section could not be decoded (RFC 9204 §8.3). */
    const val QPACK_DECOMPRESSION_FAILED: Long = 0x0200

    /** `QPACK_ENCODER_STREAM_ERROR` — an error on the QPACK encoder stream (RFC 9204 §8.3). */
    const val QPACK_ENCODER_STREAM_ERROR: Long = 0x0201

    /** `QPACK_DECODER_STREAM_ERROR` — an error on the QPACK decoder stream (RFC 9204 §8.3). */
    const val QPACK_DECODER_STREAM_ERROR: Long = 0x0202
}
