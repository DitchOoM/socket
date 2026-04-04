package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectionOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * QUIC engine — creates connections to remote hosts.
 *
 * Interface so platform implementations can be swapped and tests can mock.
 * Platform-specific defaults provided by [defaultQuicEngine].
 */
interface QuicEngine {
    /**
     * Establish a QUIC connection to [hostname]:[port].
     *
     * Performs the QUIC handshake (including TLS 1.3) and returns an
     * [Established][QuicConnectionState.Established] connection, or throws on failure.
     */
    suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions = ConnectionOptions(),
        timeout: Duration = 15.seconds,
    ): QuicConnection

    /** Release resources held by this engine. */
    fun close()
}

/**
 * Platform-specific default [QuicEngine] implementation.
 *
 * - Apple: Network.framework (NWConnection with QUIC parameters)
 * - Linux K/Native: quiche via cinterop
 * - JVM/Android: quiche via JNI
 */
expect fun defaultQuicEngine(): QuicEngine
