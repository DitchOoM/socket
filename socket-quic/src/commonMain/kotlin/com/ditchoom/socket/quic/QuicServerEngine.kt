package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * QUIC server engine — binds a UDP socket and accepts incoming connections.
 *
 * Platform-specific defaults provided by [defaultQuicServerEngine].
 */
interface QuicServerEngine {
    /**
     * Bind to [port] and start accepting QUIC connections.
     *
     * @param port UDP port to bind (0 for OS-assigned ephemeral port)
     * @param host interface to bind (null for all interfaces)
     * @param tlsConfig server TLS certificate and key
     * @param quicOptions QUIC transport configuration
     * @return a [QuicServer] that emits accepted connections
     */
    suspend fun bind(
        port: Int = 0,
        host: String? = null,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration = 15.seconds,
    ): QuicServer

    /** Release resources held by this engine. */
    fun close()
}

/**
 * Platform-specific default [QuicServerEngine] implementation.
 *
 * Prefer [withQuicServerEngine] — it owns construction and release so a
 * caller cannot leak the engine on an exception or cancellation path.
 * Direct use of this factory requires explicit `close()` in a `finally`.
 */
expect fun defaultQuicServerEngine(): QuicServerEngine

/**
 * Scope-only [QuicServerEngine] construction: allocates an engine, runs
 * [block] with it, and closes the engine in `finally` — including on
 * exceptional and cancellation paths.
 *
 * This is the canonical way to use a server engine. The reference never
 * escapes the block, so callers can't forget to close it (the Rust borrow
 * pattern in Kotlin). See `DRIVER_REDESIGN.md` → "Engine lifecycle" for the
 * full rationale; in short, leaked engines on small CI runners accumulate
 * coroutine scopes + native quiche state until dispatchers starve.
 */
suspend fun <R> withQuicServerEngine(block: suspend (QuicServerEngine) -> R): R {
    val engine = defaultQuicServerEngine()
    try {
        return block(engine)
    } finally {
        engine.close()
    }
}
