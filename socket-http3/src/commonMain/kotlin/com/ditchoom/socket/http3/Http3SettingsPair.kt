package com.ditchoom.socket.http3

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec

/**
 * A single HTTP/3 SETTINGS parameter (RFC 9114 §7.2.4): an identifier/value pair, each a
 * QUIC variable-length integer.
 *
 * This is the first annotation-driven codec in the module and deliberately the simplest
 * one: it validates that the buffer-codec KSP-for-main pipeline generates
 * `Http3SettingsPairCodec` into `commonMain` for every target, and that a custom
 * `@UseCodec(QuicVarIntCodec)` scalar codec round-trips through the generated code.
 *
 * (The top-level frame *dispatch* — Type/Length varints selecting a frame variant — is
 * hand-written in a later step, because the annotation framework's sealed-union dispatch
 * is single-fixed-byte-discriminator only and cannot express HTTP/3's varint frame type.)
 */
@ProtocolMessage
data class Http3SettingsPair(
    @UseCodec(QuicVarIntCodec::class) val identifier: Long,
    @UseCodec(QuicVarIntCodec::class) val value: Long,
)
