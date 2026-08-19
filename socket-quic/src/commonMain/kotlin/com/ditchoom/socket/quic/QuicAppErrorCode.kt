package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

/**
 * A QUIC application (protocol) error code — the value an application layer attaches to
 * RESET_STREAM / STOP_SENDING (RFC 9000 §19.4–19.5). A 62-bit varint on the wire, so [value] is
 * bounded to `[0, 2^62)` and construction enforces it.
 *
 * Typed so it cannot be mixed with the other `Long`s it travels beside (stream ids, byte counts,
 * native out-parameters), and so a log line reads `0x10c` — the notation application protocols
 * define their registries in (HTTP/3's `H3_REQUEST_CANCELLED` is 0x10c) — instead of `268`. Which
 * protocol's registry [value] belongs to is the higher layer's knowledge: WebTransport, for
 * example, packs its own 32-bit space into a reserved band of this one.
 */
@JvmInline
value class QuicAppErrorCode(
    val value: Long,
) {
    init {
        require(value in 0..MAX_VARINT) { "application error codes are 62-bit varints, got $value" }
    }

    override fun toString(): String = "0x${value.toString(16)}"

    companion object {
        /** RFC 9000 §16: the largest value a variable-length integer can carry. */
        const val MAX_VARINT = (1L shl 62) - 1
    }
}
