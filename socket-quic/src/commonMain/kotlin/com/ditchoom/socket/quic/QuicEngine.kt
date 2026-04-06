package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.ConnectionContext
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
        val context = ConnectionContext(connectionOptions)
        val mux = QuicStreamMux(this, codec, context)
        mux.block()
    }

/**
 * Platform-specific default [QuicEngine] implementation.
 */
expect fun defaultQuicEngine(): QuicEngine
