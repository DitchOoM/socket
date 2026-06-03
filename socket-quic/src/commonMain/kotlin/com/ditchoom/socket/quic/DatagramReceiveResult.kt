package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import kotlin.jvm.JvmInline

/**
 * Outcome of [QuicScope.receiveDatagram]. Exhaustive — a `when` over it forces callers to handle
 * both the data case and the connection-closed case, so there is no nullable/primitive sentinel to
 * misread (mirrors the sealed style of [QuicError] / [StreamRecvResult] / [MigrationResult]).
 *
 * Single-field variants are `@JvmInline value class` for consistency with [QuicStreamId]; note that
 * when returned through the [DatagramReceiveResult] supertype they box at that boundary, so this is
 * for descriptiveness, not a zero-allocation guarantee.
 */
sealed interface DatagramReceiveResult {
    /**
     * A datagram arrived. Ownership of [buffer] transfers to the caller, who must release it
     * (`freeNativeMemory()`); for a pooled [com.ditchoom.buffer.BufferFactory] that returns it to the pool.
     */
    @JvmInline
    value class Received(
        val buffer: ReadBuffer,
    ) : DatagramReceiveResult

    /** The connection is gone; no further datagrams will arrive. [error] is the structured close reason. */
    @JvmInline
    value class ConnectionClosed(
        val error: QuicError,
    ) : DatagramReceiveResult
}
