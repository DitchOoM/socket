package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Establish a QUIC connection to [hostname]:[port] and run [block] with the
 * resulting [QuicScope]. Suspends through the TLS 1.3 handshake; throws if
 * the handshake fails. When [block] returns (normally, exceptionally, or via
 * cancellation), the connection is closed (QUIC CONNECTION_CLOSE) and all
 * resources are released.
 *
 * This is the only client-facing entry point — there is no `QuicEngine`
 * layer because quiche itself has no analog and the previous factory shape
 * was pure overhead that leaked on dropped error paths. The scope-only
 * block boundary is the lifecycle. See `DRIVER_REDESIGN.md` →
 * "Engine lifecycle" for the rationale.
 */
expect suspend fun <R> withQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig = TransportConfig(),
    timeout: Duration = 15.seconds,
    block: suspend QuicScope.() -> R,
): R

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
