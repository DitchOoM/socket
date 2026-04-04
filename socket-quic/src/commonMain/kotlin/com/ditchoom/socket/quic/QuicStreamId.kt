package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

/**
 * Strongly-typed QUIC stream identifier. Prevents accidental misuse of raw Long values.
 *
 * Stream ID encoding (RFC 9000 §2.1):
 * - Bit 0: initiator (0 = client, 1 = server)
 * - Bit 1: directionality (0 = bidirectional, 1 = unidirectional)
 */
@JvmInline
value class QuicStreamId(
    val id: Long,
) : Comparable<QuicStreamId> {
    init {
        require(id >= 0) { "Stream ID must be non-negative, got $id" }
    }

    val isClientInitiated: Boolean get() = id and 0x1L == 0L
    val isServerInitiated: Boolean get() = !isClientInitiated
    val isBidirectional: Boolean get() = id and 0x2L == 0L
    val isUnidirectional: Boolean get() = !isBidirectional

    override fun compareTo(other: QuicStreamId): Int = id.compareTo(other.id)

    override fun toString(): String = "QuicStreamId($id)"
}
