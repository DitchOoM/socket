package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * A QUIC connection scope — the receiver inside [QuicEngine.connect] and [QuicServer.connections].
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
}
