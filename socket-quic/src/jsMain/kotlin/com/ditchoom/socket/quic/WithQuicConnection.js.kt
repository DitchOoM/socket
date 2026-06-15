package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * JS [withQuicConnection] — placeholder.
 *
 * QUIC on JS is not yet implemented. A koffi-backed Node binding was
 * explored and deferred on zero-copy grounds; see memory
 * `socket_quic_js_koffi_deferred.md` and branch `feature/socket-quic-js-wip`
 * for the attempt. Browsers have no raw UDP access at all (WebTransport is a
 * possible future path but requires a platform API, not FFI).
 *
 * Throws [UnsupportedOperationException] — not [TODO] — so callers using
 * `catch (Exception)` get a cleanly catchable signal, not an [Error].
 */
actual suspend fun <R> withQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
    block: suspend QuicScope.() -> R,
): R = throw UnsupportedOperationException("QUIC is not yet implemented on JS. Track feature/socket-quic-js-wip for progress.")
