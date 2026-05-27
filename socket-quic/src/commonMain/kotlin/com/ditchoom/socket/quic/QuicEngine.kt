package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.ConnectionOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * QUIC engine — establishes connections to remote hosts.
 *
 * Connections are scope-based: [connect] suspends through the TLS 1.3 handshake,
 * then runs the provided block with an established [QuicScope]. When the block
 * returns (or throws), the connection is closed automatically. If the connection
 * dies, the block is cancelled.
 *
 * Platform-specific defaults provided by [defaultQuicEngine].
 */
interface QuicEngine {
    /**
     * Establish a QUIC connection and run [block] with it.
     *
     * Suspends during the handshake. If the handshake fails, throws.
     * When [block] returns, the connection is closed (QUIC CONNECTION_CLOSE).
     * If the connection dies mid-block, [block] is cancelled.
     */
    suspend fun <R> connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions = ConnectionOptions(),
        timeout: Duration = 15.seconds,
        block: suspend QuicScope.() -> R,
    ): R

    /** Release resources held by this engine. */
    fun close()
}

/**
 * Convenience: connect and run a typed [StreamMux] session.
 */
suspend fun <T, R> QuicEngine.connectMux(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    codec: Codec<T>,
    connectionOptions: ConnectionOptions = ConnectionOptions(),
    timeout: Duration = 15.seconds,
    block: suspend StreamMux<T>.() -> R,
): R =
    connect(hostname, port, quicOptions, connectionOptions, timeout) {
        val mux = QuicStreamMux(this, codec, connectionOptions)
        mux.block()
    }

/**
 * Platform-specific default [QuicEngine] implementation.
 *
 * Prefer [withQuicEngine] — it owns construction and release so a caller
 * cannot leak the engine on an exception or cancellation path. Direct use
 * of this factory requires explicit `close()` in a `finally`.
 */
expect fun defaultQuicEngine(): QuicEngine

/**
 * Scope-only [QuicEngine] construction: allocates an engine, runs [block]
 * with it, and closes the engine in `finally` — including on exceptional
 * and cancellation paths.
 *
 * This is the canonical way to use a client engine. The reference never
 * escapes the block, so callers can't forget to close it. See
 * `DRIVER_REDESIGN.md` → "Engine lifecycle" for the rationale.
 */
suspend fun <R> withQuicEngine(block: suspend (QuicEngine) -> R): R {
    val engine = defaultQuicEngine()
    try {
        return block(engine)
    } finally {
        engine.close()
    }
}
