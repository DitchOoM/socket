package com.ditchoom.socket.quic

import com.ditchoom.socket.transport.ByteStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A QUIC connection supporting multiplexed streams.
 *
 * Each stream is exposed as a [ByteStream] via [openStream] (client-initiated)
 * or [acceptStream] / [streams] (peer-initiated).
 *
 * State transitions are observable via [state] and follow [QuicConnectionState] semantics.
 */
interface QuicConnection {
    /** Current connection state. Never emits impossible transitions. */
    val state: StateFlow<QuicConnectionState>

    /** Open a new client-initiated bidirectional stream. */
    suspend fun openStream(): QuicByteStream

    /** Accept the next peer-initiated stream, suspending until one arrives. */
    suspend fun acceptStream(): QuicByteStream

    /** Flow of all peer-initiated streams. Completes when the connection closes. */
    fun streams(): Flow<QuicByteStream>

    /** Gracefully close the connection, sending CONNECTION_CLOSE with [error]. */
    suspend fun close(error: QuicError = QuicError.NoError)
}
