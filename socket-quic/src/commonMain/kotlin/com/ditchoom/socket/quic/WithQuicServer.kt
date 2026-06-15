package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bind a QUIC server on [port] and run [block] with the resulting
 * [QuicServer]. When [block] returns (normally, exceptionally, or via
 * cancellation), the server is closed — UDP socket closed, all in-flight
 * drivers destroyed, all handler coroutines cancelled.
 *
 * This is the server-facing entry point and it owns the lifecycle: it asks the
 * platform's [QuicEngine][platformDefaultQuicEngine] to [bind][QuicEngine.bind],
 * runs [block], and closes the server in a `finally`. The engine is a constructor,
 * not a factory the caller babysits — the returned [QuicServer] never escapes this
 * scope. The scope-only block boundary remains the lifecycle.
 *
 * @param port UDP port to bind (0 for OS-assigned ephemeral port)
 * @param host interface to bind (null for all interfaces)
 * @param tlsConfig server TLS certificate and key
 * @param quicOptions QUIC transport configuration
 */
suspend fun <R> withQuicServer(
    port: Int = 0,
    host: String? = null,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration = 15.seconds,
    block: suspend QuicServer.() -> R,
): R {
    val server = platformDefaultQuicEngine.bind(port, host, tlsConfig, quicOptions, timeout)
    return try {
        server.block()
    } finally {
        server.close()
    }
}
