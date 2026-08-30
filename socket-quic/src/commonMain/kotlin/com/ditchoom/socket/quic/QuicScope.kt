package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * The [QuicScope.pathState] default: one process-wide flow permanently holding [QuicPathState.Original].
 * Private to this file and never mutated, so every scope that does not override `pathState` shares it
 * instead of minting a `MutableStateFlow` per read.
 */
private val NeverMigratedPath: StateFlow<QuicPathState> = MutableStateFlow(QuicPathState.Original)

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
    /**
     * The ALPN protocol negotiated during this connection's TLS 1.3 handshake (RFC 7301). QUIC
     * mandates ALPN, so an established connection always has exactly one negotiated protocol —
     * one of the entries the local endpoint offered in [QuicOptions.alpnProtocols].
     *
     * Inside a [withQuicConnection] / [QuicServer.connections] block the connection is established
     * by contract, so this is always readable there. On the server it is the demultiplexing key
     * when one listener offers several protocols (see [connectionsByAlpn]): route the accepted
     * connection to the stack that speaks what the client negotiated.
     *
     * Defaults to [UnsupportedOperationException] for scope implementations that cannot know the
     * negotiated protocol; [QuicConnection] overrides it by deriving from
     * [QuicConnectionState.Established], which every driver-backed platform populates.
     */
    val negotiatedAlpn: String
        get() = throw UnsupportedOperationException("Negotiated ALPN is not available on this platform")

    /**
     * Which connection this is — the stable session id plus the current wire CID.
     *
     * Declared here, not only on [QuicConnection], because this is the receiver user code actually has
     * inside a `withQuicConnection { }` / `QuicServer.connections { }` block. Identity that a caller
     * cannot reach from where they write their log line is identity that does not exist; the first test
     * written against it caught exactly that.
     *
     * Defaults to throwing for scope implementations with no connection behind them, matching
     * [negotiatedAlpn]. [QuicConnection] redeclares it abstract, so every real backend must answer.
     */
    val identity: QuicConnectionIdentity
        get() = throw UnsupportedOperationException("Connection identity is not available on this platform")

    /**
     * What the network was doing, for correlating this connection against a
     * [com.ditchoom.socket.NetworkMonitor]. See [QuicConnection.networkAtClose].
     */
    val networkAtClose: NetworkAtClose get() = NetworkAtClose.NotObserved

    /**
     * The [BufferFactory] this connection allocates from — the one passed via
     * [TransportConfig.bufferFactory][com.ditchoom.socket.TransportConfig.bufferFactory] (default
     * [BufferFactory.Default]). Allocate your send buffers and datagrams from here so they share the
     * connection's allocation strategy (e.g. a pooled or deterministic factory) instead of reaching
     * for a global. Pair the allocation with `use { }` so it's freed even if the write throws:
     *
     * ```kotlin
     * bufferFactory.allocate(11).use { out ->
     *     out.writeString("hello quic!", Charset.UTF8)
     *     out.resetForRead()
     *     stream.write(out)
     * }
     * ```
     */
    val bufferFactory: BufferFactory

    /**
     * What this connection's data plane requires of the buffers handed to [QuicByteStream.write] and
     * to [datagramChannel]`.send` — read it before choosing a [BufferFactory] for outbound data.
     * [bufferFactory] always satisfies it; this is for code that allocates elsewhere (a codec's own
     * pool, a portable helper written against `BufferFactory.Default`) and needs to know whether that
     * is acceptable here. See [QuicCapabilities.requiresNativeMemoryBuffers] for the contract and the
     * typed rejection ([QuicNativeMemoryRequiredException]) a violation produces.
     *
     * Defaults to throwing for scope implementations with no connection behind them, matching
     * [identity]. [QuicConnection] redeclares it abstract, so every real backend must answer.
     */
    val capabilities: QuicCapabilities
        get() = throw UnsupportedOperationException("Connection capabilities are not available on this platform")

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
     * Abort the connection with an application-defined error code — a CONNECTION_CLOSE frame of
     * type `0x1d` (RFC 9000 §19.19) carrying [errorCode]. Unlike the normal block-boundary close
     * (which sends a NO_ERROR close), this lets a layered protocol surface a violation to the peer
     * with its own error code — e.g. an HTTP/3 error code (RFC 9114 §8.1). After it returns the
     * connection is closed and the scope block unwinds.
     *
     * Defaults to [UnsupportedOperationException]; quiche-backed and Apple connections override it.
     */
    suspend fun closeWithError(errorCode: Long): Unit =
        throw UnsupportedOperationException("Application-coded connection close is not supported on this platform")

    /**
     * Actively migrate the connection to a new local path (RFC 9000 §9). The driver opens a UDP socket
     * at [target], probes the path, and switches the connection's active path once the peer validates
     * it. Streams keep flowing across the switch.
     *
     * **Suspends for the whole operation** — it returns only once the path has validated and the active
     * path has switched, or the attempt has failed. That is what makes a second call while one is in
     * flight impossible for a sequential caller, and it is why automatic migration needs no timer.
     *
     * The answer is always typed: [MigrationResult.Succeeded] carries the endpoint the platform
     * **resolved**, and every other outcome is an [MigrationResult.Unmoved] leaf saying whether a later
     * attempt could ever differ. The default implementation is
     * [MigrationResult.Unmoved.Impossible.BackendCannotMigrate] — an engine with no path factory behind
     * it.
     */
    suspend fun migrate(target: MigrationTarget = MigrationTarget.FreshLocalEndpoint): MigrationResult =
        MigrationResult.Unmoved.Impossible.BackendCannotMigrate

    /**
     * Current migration/path state. Defaults to a connection that has never migrated.
     *
     * One shared, immutable flow rather than a fresh `MutableStateFlow` per read: the old default
     * allocated a new flow on **every** access, so two reads of the same connection's `pathState` were
     * different objects and a collector on one could never see the other's value.
     */
    val pathState: StateFlow<QuicPathState>
        get() = NeverMigratedPath

    // --- Unreliable datagrams (RFC 9221) ---
    // Folded onto the buffer-flow datagram trichotomy: [datagramChannel] is this connection's
    // [ConnectedDatagramChannel] over its RFC-9221 datagrams, and the four legacy methods
    // below are deprecated delegating shims over it. Available only when [QuicOptions.datagrams] was
    // set; the defaults make a non-datagram connection behave correctly (channel access throws,
    // maxWritableSize is 0). Platforms that support datagrams override [remoteAddress] + [datagramChannel].

    /**
     * This connection's remote endpoint — the single peer every datagram on [datagramChannel] is
     * addressed to and from (a connected QUIC connection has exactly one). Exposed so received
     * [Datagram]s carry a real, inspectable [Datagram.peer] (buffer-flow requires a non-null peer) and
     * an ICE/relay layer can read the path. Defaults to [UnsupportedOperationException] on platforms
     * without datagram support; quiche-backed and Apple connections override it.
     */
    @ExperimentalDatagramApi
    val remoteAddress: SocketAddress
        get() = throw UnsupportedOperationException("QUIC datagrams are not supported on this platform")

    /**
     * This connection's RFC-9221 unreliable datagrams as a buffer-flow [ConnectedDatagramChannel] — the
     * connected, single-peer datagram endpoint (the datagram analogue of [openStream] / [streams] over
     * the shared datagram trichotomy). Every received [Datagram]'s peer is [remoteAddress];
     * [com.ditchoom.buffer.flow.DatagramSink.maxWritableSize] tracks the current path MTU and is `0`
     * when datagrams are not enabled (not set locally, or the peer never advertised
     * `max_datagram_frame_size`). Sends read the payload zero-copy (the caller retains ownership) and,
     * where [capabilities] says [QuicCapabilities.requiresNativeMemoryBuffers], the payload must be
     * native-memory-backed — the channel's own `capabilities.requiresNativeMemoryBuffers` restates the
     * same requirement in buffer-flow's vocabulary, and a heap payload is rejected with a
     * [QuicNativeMemoryRequiredException] before anything is enqueued. The connected refinement has no
     * destination parameter at all — a QUIC datagram flow has one implicit peer — and the IP
     * control-plane `options` do not apply. The returned instance is stable for the connection's life.
     *
     * Defaults to [UnsupportedOperationException]; platforms that support datagrams override this.
     */
    @ExperimentalDatagramApi
    fun datagramChannel(): ConnectedDatagramChannel =
        throw UnsupportedOperationException("QUIC datagrams are not supported on this platform")

    /**
     * Send one unreliable datagram (RFC 9221). The whole [buffer] is one datagram; it is delivered
     * at most once, unordered, with no retransmission. Suspends only while the send queue is full
     * (backpressure); the bytes are read zero-copy and the caller retains ownership of [buffer].
     *
     * @throws IllegalArgumentException if `buffer.remaining()` exceeds the current max datagram size.
     * @throws QuicCloseException if the connection is closed.
     */
    @Deprecated(
        "Folded onto the buffer-flow datagram trichotomy.",
        ReplaceWith("datagramChannel().send(buffer)"),
    )
    @OptIn(ExperimentalDatagramApi::class)
    suspend fun sendDatagram(buffer: ReadBuffer): Unit = datagramChannel().send(buffer)

    /**
     * Receive the next unreliable datagram. Suspends until one arrives or the connection closes.
     * Returns [DatagramReceiveResult.Received] (ownership of the buffer transfers to the caller) or
     * [DatagramReceiveResult.ConnectionClosed] — never null.
     */
    @Deprecated(
        "Folded onto the buffer-flow datagram trichotomy; use datagramChannel().receive().",
    )
    @OptIn(ExperimentalDatagramApi::class)
    suspend fun receiveDatagram(): DatagramReceiveResult =
        when (val result = datagramChannel().receive()) {
            is DatagramReadResult.Received -> DatagramReceiveResult.Received(result.datagram.payload)
            is DatagramReadResult.Closed ->
                DatagramReceiveResult.ConnectionClosed(result.reason as? QuicError ?: QuicError.NoError)
        }

    /**
     * Flow of incoming unreliable datagrams — a thin loop over [datagramChannel] for parity with
     * [streams]. Completes when the connection closes. Each emitted buffer is owned by the collector,
     * which must release it (same contract as [com.ditchoom.data.Reader.readFlow]).
     */
    @Deprecated(
        "Folded onto the buffer-flow datagram trichotomy; collect datagramChannel() directly.",
    )
    @OptIn(ExperimentalDatagramApi::class)
    fun datagrams(): Flow<ReadBuffer> =
        flow {
            val channel = datagramChannel()
            while (true) {
                when (val result = channel.receive()) {
                    is DatagramReadResult.Received -> emit(result.datagram.payload)
                    is DatagramReadResult.Closed -> return@flow
                }
            }
        }

    /**
     * Maximum payload a single datagram may carry right now — [MaxDatagramSize.Bytes] when datagrams
     * can be sent, or [MaxDatagramSize.Unavailable] when they cannot (not enabled locally, or the peer
     * never advertised `max_datagram_frame_size`). Tracks the current path MTU, so the byte count can
     * change over the connection's life.
     */
    @Deprecated(
        "Folded onto the buffer-flow datagram trichotomy.",
        ReplaceWith("datagramChannel().maxWritableSize"),
    )
    @OptIn(ExperimentalDatagramApi::class)
    fun maxDatagramSize(): MaxDatagramSize =
        when (val bytes = datagramChannel().maxWritableSize) {
            0 -> MaxDatagramSize.Unavailable
            else -> MaxDatagramSize.Bytes(bytes)
        }
}
