package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

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

    /**
     * Open a new locally-initiated unidirectional stream (RFC 9000 §2.1) — the client-to-server
     * uni streams HTTP/3 needs (control + QPACK encoder/decoder, RFC 9114 §6.2). The caller writes
     * the stream-type prefix as the first bytes, and `close()` sends FIN. The returned stream's
     * [QuicByteStream.streamId] is unidirectional ([QuicStreamId.isUnidirectional]).
     *
     * Defaults to [UnsupportedOperationException]; platforms that support it (quiche-backed, Apple)
     * override this.
     */
    suspend fun openUniStream(): QuicByteStream =
        throw UnsupportedOperationException("Unidirectional QUIC streams are not supported on this platform")

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

    // --- Unreliable datagrams (RFC 9221) ---
    // Available only when [QuicOptions.datagrams] was set. The default implementations make a
    // non-datagram connection behave correctly: send throws, receive throws, the flow is empty,
    // and the max size is null. Platforms that support datagrams override all four.

    /**
     * Send one unreliable datagram (RFC 9221). The whole [buffer] is one datagram; it is delivered
     * at most once, unordered, with no retransmission. Suspends only while the send queue is full
     * (backpressure); the bytes are read zero-copy and the caller retains ownership of [buffer].
     *
     * @throws IllegalArgumentException if `buffer.remaining()` exceeds the current [maxDatagramSize].
     * @throws IllegalStateException if datagrams are [MaxDatagramSize.Unavailable] (not enabled).
     * @throws QuicCloseException if the connection is closed.
     */
    suspend fun sendDatagram(buffer: ReadBuffer): Unit =
        throw UnsupportedOperationException("QUIC datagrams are not supported on this platform")

    /**
     * Receive the next unreliable datagram. Suspends until one arrives or the connection closes.
     * Returns [DatagramReceiveResult.Received] (ownership of the buffer transfers to the caller) or
     * [DatagramReceiveResult.ConnectionClosed] — never null.
     */
    suspend fun receiveDatagram(): DatagramReceiveResult =
        throw UnsupportedOperationException("QUIC datagrams are not supported on this platform")

    /**
     * Flow of incoming unreliable datagrams — a thin loop over [receiveDatagram] for parity with
     * [streams]. Completes when the connection closes. Each emitted buffer is owned by the collector,
     * which must release it (same contract as [com.ditchoom.data.Reader.readFlow]).
     */
    fun datagrams(): Flow<ReadBuffer> = emptyFlow()

    /**
     * Maximum payload a single [sendDatagram] may carry right now — [MaxDatagramSize.Bytes] when
     * datagrams can be sent, or [MaxDatagramSize.Unavailable] when they cannot (not enabled locally,
     * or the peer never advertised `max_datagram_frame_size`). Tracks the current path MTU, so the
     * byte count can change over the connection's life.
     */
    fun maxDatagramSize(): MaxDatagramSize = MaxDatagramSize.Unavailable
}
