package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

/**
 * A raw return code from quiche's `enum quiche_error` (quiche.h), typed so it cannot be mixed with
 * the other `Int`/`Long`s that cross the FFI boundary (lengths, native addresses, stream ids) and so
 * a log line reads `StreamReset(-16)` instead of `-16`.
 *
 * A value class over the raw code, not a sealed enumeration, on purpose: the set belongs to quiche
 * and grows with quiche versions, so an exhaustive `when` over it here would go stale and lie about
 * totality. The codes the driver *acts* on are already lifted into sealed variants at their decision
 * points ([StreamRecvResult.Reset], [StreamRecvResult.Done], [MigrateOutcome.Migrated]); what remains
 * is a pass-through value whose job is to be carried faithfully and printed readably.
 */
@JvmInline
value class QuicheErrorCode(
    val raw: Int,
) {
    override fun toString(): String =
        when (raw) {
            -1 -> "Done($raw)"
            -2 -> "BufferTooShort($raw)"
            -3 -> "UnknownVersion($raw)"
            -4 -> "InvalidFrame($raw)"
            -5 -> "InvalidPacket($raw)"
            -6 -> "InvalidState($raw)"
            -7 -> "InvalidStreamState($raw)"
            -8 -> "InvalidTransportParam($raw)"
            -9 -> "CryptoFail($raw)"
            -10 -> "TlsFail($raw)"
            -11 -> "FlowControl($raw)"
            -12 -> "StreamLimit($raw)"
            -13 -> "FinalSize($raw)"
            -14 -> "CongestionControl($raw)"
            -15 -> "StreamStopped($raw)"
            -16 -> "StreamReset($raw)"
            -17 -> "IdLimit($raw)"
            -18 -> "OutOfIdentifiers($raw)"
            -19 -> "KeyUpdate($raw)"
            -20 -> "CryptoBufferExceeded($raw)"
            -21 -> "InvalidAckRange($raw)"
            -22 -> "OptimisticAckDetected($raw)"
            -23 -> "InvalidDcidInitialization($raw)"
            else -> "QuicheError($raw)"
        }
}

/**
 * A DCID sequence number (RFC 9000 §5.1.1) — which of the peer-issued destination connection IDs a
 * path uses. Typed because [QuicheApi] is full of `Long`s that are *native addresses*; a sequence
 * number that compiles where a pointer belongs (or vice versa) is exactly the mix-up
 * [QuicheCmd]'s "no raw Long mixing" rule exists to make impossible. Sequence 0 is the initial
 * connection ID; each successful `connMigrate` reports the sequence now active on the new path,
 * and RFC 9000 §9.5 retirement names the sequence of the path being left.
 */
@JvmInline
value class DcidSeq(
    val value: Long,
)
