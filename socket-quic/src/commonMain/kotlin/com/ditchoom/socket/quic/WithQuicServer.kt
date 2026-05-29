package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bind a QUIC server on [port] and run [block] with the resulting
 * [QuicServer]. When [block] returns (normally, exceptionally, or via
 * cancellation), the server is closed — UDP socket closed, all in-flight
 * drivers destroyed, all handler coroutines cancelled.
 *
 * This is the only server-facing entry point — there is no
 * `QuicServerEngine` layer because quiche itself has no analog and the
 * previous factory shape was pure overhead that leaked on dropped error
 * paths. The scope-only block boundary is the lifecycle. See
 * `DRIVER_REDESIGN.md` → "Engine lifecycle" for the rationale.
 *
 * @param port UDP port to bind (0 for OS-assigned ephemeral port)
 * @param host interface to bind (null for all interfaces)
 * @param tlsConfig server TLS certificate and key
 * @param quicOptions QUIC transport configuration
 */
expect suspend fun <R> withQuicServer(
    port: Int = 0,
    host: String? = null,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration = 15.seconds,
    block: suspend QuicServer.() -> R,
): R
