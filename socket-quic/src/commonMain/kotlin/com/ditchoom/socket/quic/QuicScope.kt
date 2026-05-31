package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A QUIC connection scope — the receiver inside [withQuicConnection] and [QuicServer.connections].
 *
 * Extends [CoroutineScope] so you can [launch][kotlinx.coroutines.launch] child coroutines
 * that are tied to the connection lifetime. When the connection closes, all children are
 * cancelled automatically via structured concurrency.
 *
 * **TOCTOU-free guarantees:**
 * - If your code is running inside a [QuicScope] block, the connection is alive.
 * - When the block returns (normally or via exception), the connection is closed.
 * - If the connection dies (peer close, timeout), the block is cancelled.
 * - Streams not explicitly [closed][QuicByteStream.close] are force-closed when the scope ends.
 *
 * There is no `close()` method — the block boundary IS the lifecycle.
 * There is no connection state to check — if you're in the block, it's established.
 */
interface QuicScope : CoroutineScope {
    /** Open a new locally-initiated bidirectional stream. Caller should close() when done (sends FIN). */
    suspend fun openStream(): QuicByteStream

    /** Accept the next peer-initiated stream. Suspends until one arrives or scope is cancelled. */
    suspend fun acceptStream(): QuicByteStream

    /** Flow of all peer-initiated streams. Completes when the connection closes. */
    fun streams(): Flow<QuicByteStream>

    /**
     * Actively migrate the connection to a new local path (RFC 9000 §9). The driver opens a UDP
     * socket bound to [localHost]:[localPort] (null host = default interface, 0 port = ephemeral),
     * probes the path, and switches the connection's active path once the peer validates it. Streams
     * keep flowing across the switch.
     *
     * Returns [MigrationResult.Unsupported] on platforms without controllable migration (Apple, JS)
     * and on server-accepted connections. The default implementation is [MigrationResult.Unsupported].
     */
    suspend fun migrate(
        localHost: String? = null,
        localPort: Int = 0,
    ): MigrationResult = MigrationResult.Unsupported

    /** Current migration/path state. Defaults to a never-migrating [PathInfo]. */
    val pathState: StateFlow<PathInfo>
        get() = MutableStateFlow(PathInfo())
}
