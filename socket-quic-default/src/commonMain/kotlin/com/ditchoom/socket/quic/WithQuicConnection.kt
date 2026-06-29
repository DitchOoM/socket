package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Establish a QUIC connection to [hostname]:[port] and run [block] with the
 * resulting [QuicScope]. Suspends through the TLS 1.3 handshake; throws if
 * the handshake fails. When [block] returns (normally, exceptionally, or via
 * cancellation), the connection is closed (QUIC CONNECTION_CLOSE) and all
 * resources are released.
 *
 * This is the client-facing entry point and it owns the lifecycle: it asks the
 * platform's [QuicEngine][defaultQuicEngine] to [connect][QuicEngine.connect],
 * runs [block], and closes the connection in a `finally`. The engine is a
 * constructor, not a factory the caller babysits — the returned [QuicConnection]
 * never escapes this scope, so there is nothing to leak on a dropped error path.
 * The scope-only block boundary remains the lifecycle.
 *
 * [timeout] bounds establishment *and* the block, preserving the pre-engine behavior.
 */
suspend fun <R> withQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig = TransportConfig(),
    timeout: Duration = 15.seconds,
    block: suspend QuicScope.() -> R,
): R =
    withTimeout(timeout) {
        val connection =
            defaultQuicEngine.connect(hostname, port, quicOptions, connectionOptions, timeout)
        try {
            connection.block()
        } finally {
            connection.close()
        }
    }

/**
 * Convenience: open a QUIC connection and run a typed [StreamMux] session
 * inside it. Wraps [withQuicConnection] + [QuicStreamMux].
 */
suspend fun <T, R> withQuicMux(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    codec: Codec<T>,
    connectionOptions: TransportConfig = TransportConfig(),
    timeout: Duration = 15.seconds,
    block: suspend StreamMux<T>.() -> R,
): R =
    withQuicConnection(hostname, port, quicOptions, connectionOptions, timeout) {
        val mux = QuicStreamMux(this, codec, connectionOptions)
        mux.block()
    }
