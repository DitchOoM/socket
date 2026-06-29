package com.ditchoom.socket.quic

import kotlinx.coroutines.flow.StateFlow

/**
 * An established QUIC connection — extends [QuicScope] with lifecycle management. This is the
 * SPI return type of [QuicEngine.connect]: a backend module (e.g. `socket-quic-quiche`)
 * implements it, and the [withQuicConnection] wrapper consumes it.
 *
 * Users normally never name this type — they interact via [QuicScope] inside [withQuicConnection]
 * or [QuicServer.connections] blocks, which own [close]. It is public only so the engine SPI can
 * cross the module boundary between an engine module and the default bundle.
 */
interface QuicConnection : QuicScope {
    /** Current connection state (used by the withQuicConnection/withQuicServer wrappers for lifecycle management). */
    val state: StateFlow<QuicConnectionState>

    /** Close the connection with a QUIC error. Called by the scope when the block ends. */
    suspend fun close(error: QuicError = QuicError.NoError)

    /**
     * Application-coded connection close (RFC 9000 §19.19). Delegates to [close] with a
     * [QuicError.ApplicationError], which the driver maps to `quiche_conn_close(app = true, …)` so
     * [errorCode] travels on the wire. One default here covers every platform [QuicConnection].
     */
    override suspend fun closeWithError(errorCode: Long) = close(QuicError.ApplicationError(errorCode))
}
