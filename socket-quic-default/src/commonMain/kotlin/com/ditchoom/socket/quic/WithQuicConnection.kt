package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.OverflowPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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
    /**
     * Outbound queue depth and full-queue policy for every stream — see [OverflowPolicy] (#382).
     * Defaulted to the conservative pair so existing callers keep compiling and get the fix; state
     * them when a lagging peer should shed rather than apply back-pressure.
     */
    outboundCapacity: Int = CodecConnection.DEFAULT_OUTBOUND_CAPACITY,
    overflowPolicy: OverflowPolicy<T> = OverflowPolicy.Suspend,
    connectionOptions: TransportConfig = TransportConfig(),
    timeout: Duration = 15.seconds,
    block: suspend StreamMux<T>.() -> R,
): R =
    withQuicConnection(hostname, port, quicOptions, connectionOptions, timeout) {
        // Scoped like the connection it rides: the stream writers are cancelled when this returns.
        val muxScope = CoroutineScope(currentCoroutineContext() + Job())
        try {
            QuicStreamMux(this, codec, connectionOptions, muxScope, outboundCapacity, overflowPolicy).block()
        } finally {
            muxScope.cancel()
        }
    }
