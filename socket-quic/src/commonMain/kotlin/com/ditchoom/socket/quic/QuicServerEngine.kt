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
 */
expect fun defaultQuicServerEngine(): QuicServerEngine
