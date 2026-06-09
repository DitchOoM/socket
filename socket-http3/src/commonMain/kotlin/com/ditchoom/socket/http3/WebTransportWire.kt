package com.ditchoom.socket.http3

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.EncodeContext

/**
 * Wire constants and codecs for WebTransport over HTTP/3 (draft-ietf-webtrans-http3 §4 / §6,
 * RFC 9297 Capsule Protocol). Shared by the client ([Http3Connection]) and server
 * ([Http3ServerConnection]) roles via [WebTransportMux].
 *
 * The three byte-level framings WebTransport adds on top of HTTP/3 are:
 *  - **Unidirectional streams** (§4.1): the stream's first varint is [WT_UNI_STREAM_TYPE], then the
 *    Session ID varint, then raw application bytes (no HTTP/3 framing).
 *  - **Bidirectional streams** (§4.2): the stream's first varint is the [WT_BIDI_STREAM_SIGNAL]
 *    frame type, then the Session ID varint, then raw application bytes.
 *  - **Datagrams** (§4.4): the QUIC DATAGRAM payload is `Quarter Stream ID (= sessionId / 4)` then the
 *    application datagram payload, unmodified.
 *
 * Session control rides the CONNECT stream as RFC 9297 capsules **inside HTTP/3 DATA frames** —
 * [WT_CLOSE_SESSION] (graceful close with an application code + reason) and [WT_DRAIN_SESSION].
 */
internal object WebTransportWire {
    /** Unidirectional WebTransport stream type prefix (draft-ietf-webtrans-http3 §4.1). */
    const val WT_UNI_STREAM_TYPE: Long = 0x54

    /** Bidirectional WebTransport stream signal frame type (draft-ietf-webtrans-http3 §4.2). */
    const val WT_BIDI_STREAM_SIGNAL: Long = 0x41

    /** WT_CLOSE_SESSION capsule type (draft-ietf-webtrans-http3 §6): graceful session close. */
    const val WT_CLOSE_SESSION: Long = 0x2843

    /** WT_DRAIN_SESSION capsule type (draft-ietf-webtrans-http3 §4.6): graceful drain request. */
    const val WT_DRAIN_SESSION: Long = 0x78ae

    /** draft-ietf-webtrans-http3 §6: the application error message is capped at 1024 UTF-8 bytes. */
    const val MAX_CLOSE_REASON_BYTES: Int = 1024

    /**
     * Base of the WebTransport slice of the HTTP/3 error-code space (draft-ietf-webtrans-http3 §4.3).
     * A WebTransport application error code `n` maps to the HTTP/3 code `BASE + n + floor(n / 0x1e)`.
     */
    const val WT_ERROR_BASE: Long = 0x52e4a40fa8db

    /**
     * Map a 32-bit WebTransport application error code [webTransportCode] to the HTTP/3 / QUIC error
     * code that carries it on a RESET_STREAM / STOP_SENDING frame (draft-ietf-webtrans-http3 §4.3).
     *
     * The HTTP/3 error-code space is shared by every extension, so WebTransport codes are remapped to a
     * dedicated slice — `BASE + n + floor(n / 0x1e)` — that skips one value out of every 0x1f to dodge
     * the reserved GREASE codes (`0x1f * N + 0x21`). [webTransportCode] is treated as an unsigned 32-bit
     * value (the draft's domain is `0 .. 2^32 - 1`).
     */
    fun toHttp3ErrorCode(webTransportCode: Long): Long {
        val n = webTransportCode and 0xFFFFFFFFL
        return WT_ERROR_BASE + n + n / 0x1e
    }

    /**
     * Inverse of [toHttp3ErrorCode]: recover the WebTransport application error code from the HTTP/3 /
     * QUIC code observed on a peer's RESET_STREAM / STOP_SENDING (draft-ietf-webtrans-http3 §4.3,
     * `shifted = h - BASE; n = shifted - floor(shifted / 0x1f)`).
     *
     * Defined for codes inside the WebTransport slice (`h >= BASE`). A code below the slice, or one that
     * lands on a skipped GREASE slot, is not a WebTransport code; this still returns the formula's value
     * (the demux paths only feed it codes a WebTransport peer produced via [toHttp3ErrorCode]).
     */
    fun toWebTransportErrorCode(http3Code: Long): Long {
        val shifted = http3Code - WT_ERROR_BASE
        if (shifted < 0) return shifted // not a WebTransport-mapped code; surface the raw offset
        return shifted - shifted / 0x1f
    }

    /**
     * The Quarter Stream ID (RFC 9297 §2.1) that identifies datagrams for the session whose CONNECT
     * stream is [sessionId]. The CONNECT stream is always a client-initiated bidirectional stream, so
     * its id is a multiple of four and the division is exact.
     */
    fun quarterStreamId(sessionId: Long): Long = sessionId / 4

    /** Encoded byte length of a WT_CLOSE_SESSION capsule for [code] + [reason] (truncated to 1024 bytes). */
    fun closeSessionCapsuleSize(reasonUtf8Bytes: Int): Int {
        val valueLen = 4 + reasonUtf8Bytes // u32 error code + reason bytes
        return VarIntCodec.encodedLength(WT_CLOSE_SESSION) + VarIntCodec.encodedLength(valueLen.toLong()) + valueLen
    }

    /**
     * Write a WT_CLOSE_SESSION capsule (Type, Length, `Application Error Code (32)`,
     * `Application Error Message`) to [buffer]. The 32-bit code is written network order via explicit
     * bytes so it does not depend on the buffer's [com.ditchoom.buffer.ByteOrder].
     */
    fun writeCloseSessionCapsule(
        buffer: WriteBuffer,
        code: Int,
        reason: String,
        reasonUtf8Bytes: Int,
    ) {
        val valueLen = 4 + reasonUtf8Bytes
        VarIntCodec.encode(buffer, WT_CLOSE_SESSION, EncodeContext.Empty)
        VarIntCodec.encode(buffer, valueLen.toLong(), EncodeContext.Empty)
        buffer.writeByte((code ushr 24).toByte())
        buffer.writeByte((code ushr 16).toByte())
        buffer.writeByte((code ushr 8).toByte())
        buffer.writeByte(code.toByte())
        if (reasonUtf8Bytes > 0) buffer.writeString(reason, Charset.UTF8)
    }

    /**
     * Decode a WT_CLOSE_SESSION capsule **value** (the bytes after Type+Length) of [valueLength] bytes
     * from [buffer], positioned at the start of the value. Returns the application error code + reason.
     * A value shorter than the 4-byte code is malformed (draft-ietf-webtrans-http3 §6).
     */
    fun readCloseSessionValue(
        buffer: ReadBuffer,
        valueLength: Int,
    ): WebTransportCloseInfo {
        if (valueLength < 4) throw Http3StreamException("WT_CLOSE_SESSION capsule shorter than its 4-byte error code")
        val code =
            ((buffer.readByte().toInt() and 0xFF) shl 24) or
                ((buffer.readByte().toInt() and 0xFF) shl 16) or
                ((buffer.readByte().toInt() and 0xFF) shl 8) or
                (buffer.readByte().toInt() and 0xFF)
        val reasonBytes = valueLength - 4
        val reason = if (reasonBytes > 0) buffer.readString(reasonBytes, Charset.UTF8) else ""
        return WebTransportCloseInfo(code, reason)
    }
}
