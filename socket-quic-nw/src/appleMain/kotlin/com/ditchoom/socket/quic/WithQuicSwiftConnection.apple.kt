@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.toNativeData
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.nwquic26.NWQuic26Bridge
import com.ditchoom.socket.quic.nwquic26.NWQuic26Conn
import com.ditchoom.socket.quic.nwquic26.NWQuic26Listener
import com.ditchoom.socket.quic.nwquic26.NWQuic26Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.posix.getenv
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The OS-26 Swift-API QUIC backend (issue #173) — a sibling to the legacy [connectQuicGroup] /
 * [AppleQuicGroupConnection] path, built over the `NetworkConnection<QUIC>` Swift shim
 * (`NWQuic26Bridge`). Unlike the `nw_connection_group` backend, this models datagrams AND inbound
 * streams on the SAME connection, so WebTransport datagrams coexist with streams. Selected at
 * connect/bind time on macOS 26 / iOS 26+ (the OS gating lives in the engine; step 5).
 *
 * The shim hands events back via push handlers (one Swift serving Task per inbound-stream / datagram
 * loop); this Kotlin layer re-wraps them into the same `Channel<QuicByteStream>` / `Channel<ReadBuffer>`
 * shapes the group backend uses, so the `QuicConnection` / `QuicScope` contract is satisfied verbatim.
 */
internal suspend fun connectQuicSwift(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
): AppleQuicSwiftConnection {
    val datagramsEnabled = quicOptions.datagrams != null
    val serverCertHashes: List<NSData>? =
        quicOptions.serverCertificateHashes
            .map { it.value.toNativeData().nsData }
            .ifEmpty { null }
    val requireChain = quicOptions.certificateHashVerification == CertificateHashVerification.RequireBoth

    val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    val datagramChannel = if (datagramsEnabled) Channel<ReadBuffer>(Channel.UNLIMITED) else null

    val bridge = NWQuic26Bridge()
    val swiftConn =
        AppleQuicSwiftConnection(
            incomingStreams = incomingStreams,
            datagramChannel = datagramChannel,
            bufferFactory = connectionOptions.quicBufferFactory(),
            datagramsEnabled = datagramsEnabled,
            negotiatedAlpn = quicOptions.alpnProtocols.firstOrNull() ?: "",
        )

    val handle =
        bridge.connectWithHost(
            host = hostname,
            port = port.toUShort(),
            alpn = quicOptions.alpnProtocols,
            idleTimeoutMs =
                quicOptions.idleTimeout.inWholeMilliseconds
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
            maxDatagramFrameSize = if (datagramsEnabled) DATAGRAM_FRAME_SIZE_MAX.toInt() else 0,
            keepAliveMs =
                quicOptions.keepAliveInterval
                    ?.inWholeMilliseconds
                    ?.coerceAtMost(Int.MAX_VALUE.toLong())
                    ?.toInt() ?: 0,
            serverCertificateHashes = serverCertHashes,
            requireChain = requireChain,
            verifyPeer = quicOptions.verifyPeer,
            onReady = { _, _ -> }, // establishment is driven by onStateChanged below (unified client+server)
        )
    swiftConn.attach(handle, hostname, port)
    wireInbound(handle, incomingStreams, datagramChannel, tag = "cli", isConnectionLive = swiftConn::connectionLive)

    try {
        withTimeout(timeout) { swiftConn.established.await() }
        // Resolve the datagram channel so maxDatagramSize() reports the negotiated size before the first
        // sendDatagram (the channel's size is unknown until it resolves, just after `ready`).
        if (datagramsEnabled) withTimeout(timeout) { swiftConn.ensureDatagramsReady() }
    } catch (e: Throwable) {
        handle.closeWithAppErrorCode(0u)
        throw e
    }
    return swiftConn
}

// Env-gated (QUIC_NW_DIAG) trace for the in-process NW↔NW loopback race (PR #176). Pairs with the
// Swift bridge's `nwDiag` (stderr): the Swift side proves whether NW *delivered* an inbound stream;
// this Kotlin side proves whether it reached the Channel the H3/WT router consumes — bisecting an
// NW-layer drop from an above-NW routing drop. No timestamp here (the Swift line it brackets carries
// the monotonic stamp); zero cost when the env var is unset.
private val nwDiagEnabled: Boolean = getenv("QUIC_NW_DIAG") != null

private fun nwDiag(message: String) {
    if (nwDiagEnabled) println("[NWQ26-kt] $message")
}

// Wire the shim's inbound push handlers into the Kotlin channels. Safe to call before `ready` — the
// serving Tasks internally await readiness (verified by the step-3 stream tests).
private fun wireInbound(
    conn: NWQuic26Conn,
    incomingStreams: Channel<QuicByteStream>,
    datagramChannel: Channel<ReadBuffer>?,
    tag: String,
    isConnectionLive: () -> Boolean,
) {
    conn.onInboundStream { stream, id, isUni, serverInit ->
        // Real RFC 9000 wire id + directionality come straight from the new API — no synthetic-id or
        // phantom-stream filtering the legacy group path needed.
        nwDiag("$tag inbound stream id=$id uni=$isUni serverInit=$serverInit -> Kotlin channel")
        incomingStreams.trySend(QuicByteStream(QuicStreamId(id.toLong()), NWQuic26ByteStream(stream!!, isConnectionLive)))
    }
    if (datagramChannel != null) {
        conn.onDatagram { data -> datagramChannel.trySend(nsDataToReadBuffer(data!!)) }
    }
}

/** Wrap an inbound NW datagram/stream chunk as a read-positioned zero-copy [ReadBuffer]. */
private fun nsDataToReadBuffer(data: NSData): ReadBuffer {
    val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
    buffer.position(nsDataLengthInt(data))
    buffer.resetForRead()
    return buffer
}

/**
 * A [QuicConnection] backed by the OS-26 `NetworkConnection<QUIC>` Swift shim. Mirrors
 * [AppleQuicGroupConnection]'s contract (state, streams, datagrams, close) but over the new API, where
 * datagrams and inbound streams coexist. [established] is driven by [onStateChanged] (state 3 = ready),
 * so the same instance serves both the client connect-await and the server's pre-handler readiness wait.
 */
internal class AppleQuicSwiftConnection(
    private val incomingStreams: Channel<QuicByteStream>,
    private val datagramChannel: Channel<ReadBuffer>?,
    override val bufferFactory: BufferFactory,
    private val datagramsEnabled: Boolean,
    private val negotiatedAlpn: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : QuicConnection,
    CoroutineScope by scope {
    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)
    override val state: StateFlow<QuicConnectionState> = _state

    /** Completes when the connection first reaches `ready`; fails with the connect error if it never does. */
    val established = CompletableDeferred<Unit>()

    private lateinit var conn: NWQuic26Conn
    private var hostname: String = ""
    private var port: Int = 0

    private val closeClaim = AtomicInt(0)

    @Volatile
    private var closed = false

    /**
     * Whether this connection is still usable — i.e. we have NOT observed a terminal state (peer/local
     * close, idle timeout, transport failure). Used by [NWQuic26ByteStream] to tell a STREAM-scoped abort
     * from a true connection close when Network.framework hands back a code-less POSIX 57 (issue #134):
     * if the connection is still live, a failed stream write is stream-scoped, not a connection close.
     */
    internal fun connectionLive(): Boolean = !closed

    @Volatile
    private var lastMaxDatagram: MaxDatagramSize = MaxDatagramSize.Unavailable

    /** Bind the underlying shim handle + wire its state callback. Called once, right after construction. */
    fun attach(
        conn: NWQuic26Conn,
        hostname: String,
        port: Int,
    ) {
        this.conn = conn
        this.hostname = hostname
        this.port = port
        conn.onStateChanged { stateCode, errCode, desc -> onStateChanged(stateCode, errCode, desc) }
    }

    /** NW connection states (see the shim): 0=setup,1=waiting,2=preparing,3=ready,4=failed,5=cancelled. */
    private fun onStateChanged(
        stateCode: Int,
        errCode: Int,
        desc: String?,
    ) {
        when (stateCode) {
            3 -> {
                if (established.complete(Unit)) _state.value = QuicConnectionState.Established(negotiatedAlpn)
            }
            4, 5 -> {
                if (!established.isCompleted) {
                    // Pre-ready terminal: surface as the connect failure. (`.waiting` carrying a transient
                    // ENETDOWN is state 1 and is correctly ignored here — it recovers to ready.)
                    established.completeExceptionally(
                        SocketConnectionException.Refused(
                            hostname,
                            port,
                            platformError = "QUIC connect failed: code=$errCode ${desc ?: ""}",
                        ),
                    )
                } else {
                    onTransportClosed(
                        if (errCode ==
                            0
                        ) {
                            QuicError.NoError
                        } else {
                            QuicError.PlatformError(RuntimeException("NW $errCode ${desc ?: ""}"))
                        },
                    )
                }
            }
            else -> {} // setup / waiting / preparing — non-terminal
        }
    }

    /** Resolve the datagram channel so [maxDatagramSize] is accurate before the first send (no-op if disabled). */
    suspend fun ensureDatagramsReady() {
        if (!datagramsEnabled) return
        suspendCancellableCoroutine { cont ->
            conn.ensureDatagramsReady { _, _ -> if (cont.isActive) cont.resume(Unit) }
        }
    }

    override suspend fun openStream(): QuicByteStream = openStreamInternal(uni = false)

    override suspend fun openUniStream(): QuicByteStream = openStreamInternal(uni = true)

    private suspend fun openStreamInternal(uni: Boolean): QuicByteStream {
        check(!closed) { "AppleQuicSwiftConnection is closed" }
        return suspendCancellableCoroutine { cont ->
            conn.openStreamWithUni(uni) { stream, id, errCode, desc ->
                if (stream != null && errCode == 0) {
                    cont.resume(QuicByteStream(QuicStreamId(id.toLong()), NWQuic26ByteStream(stream, ::connectionLive)))
                } else if (cont.isActive) {
                    cont.resumeWithException(QuicCloseException(closeReason(), "Failed to open QUIC stream: $errCode ${desc ?: ""}"))
                }
            }
        }
    }

    override suspend fun acceptStream(): QuicByteStream {
        check(!closed) { "AppleQuicSwiftConnection is closed" }
        return incomingStreams.receive()
    }

    override fun streams(): Flow<QuicByteStream> = incomingStreams.consumeAsFlow()

    // --- datagrams (RFC 9221) ---

    override suspend fun sendDatagram(buffer: ReadBuffer) {
        if (!datagramsEnabled) {
            throw IllegalStateException("QUIC datagrams are not enabled, or the peer did not advertise support")
        }
        val remaining = buffer.remaining()
        when (val max = maxDatagramSize()) {
            is MaxDatagramSize.Unavailable ->
                throw IllegalStateException("QUIC datagrams are not enabled, or the peer did not advertise support")
            is MaxDatagramSize.Bytes ->
                require(remaining <= max.bytes) { "datagram too large: $remaining > ${max.bytes} bytes" }
        }
        if (closed) throw QuicCloseException(closeReason(), "connection closed")
        val nsData = buffer.toNativeData().nsData
        suspendCancellableCoroutine { cont ->
            conn.sendDatagram(nsData) { errCode, desc ->
                if (errCode != 0) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            QuicCloseException(
                                closeReason(QuicError.InternalError("datagram send error: $errCode")),
                                "QUIC datagram send error: $errCode ${desc ?: ""}",
                            ),
                        )
                    }
                } else if (cont.isActive) {
                    cont.resume(Unit)
                }
            }
        }
    }

    override suspend fun receiveDatagram(): DatagramReceiveResult {
        val channel =
            datagramChannel
                ?: throw UnsupportedOperationException("QUIC datagrams are not enabled on this connection")
        val result = channel.receiveCatching()
        return result.getOrNull()?.let { DatagramReceiveResult.Received(it) }
            ?: DatagramReceiveResult.ConnectionClosed(closeReason())
    }

    override fun datagrams(): Flow<ReadBuffer> {
        if (datagramChannel == null) return emptyFlow()
        return flow {
            while (true) {
                when (val result = receiveDatagram()) {
                    is DatagramReceiveResult.Received -> emit(result.buffer)
                    is DatagramReceiveResult.ConnectionClosed -> return@flow
                }
            }
        }
    }

    override fun maxDatagramSize(): MaxDatagramSize {
        if (!datagramsEnabled) return MaxDatagramSize.Unavailable
        if (closed) return lastMaxDatagram
        val bytes = conn.maxDatagramSize()
        val result = if (bytes > 0) MaxDatagramSize.Bytes(bytes) else lastMaxDatagram
        lastMaxDatagram = result
        return result
    }

    override suspend fun close(error: QuicError) {
        if (!closeClaim.compareAndSet(0, 1)) return
        closed = true
        _state.value = QuicConnectionState.Closed(error)
        val appCode = (error as? QuicError.ApplicationError)?.applicationCode?.toULong() ?: 0uL
        conn.closeWithAppErrorCode(appCode)
        incomingStreams.close()
        datagramChannel?.close()
        scope.cancel()
    }

    /** NW reported the connection failed/cancelled after `ready` (peer close, idle timeout, loss). */
    private fun onTransportClosed(reason: QuicError) {
        if (!closeClaim.compareAndSet(0, 1)) return
        closed = true
        _state.value = QuicConnectionState.Closed(reason)
        incomingStreams.close()
        datagramChannel?.close()
        conn.closeWithAppErrorCode(0u)
        scope.cancel()
    }

    private fun closeReason(fallback: QuicError = QuicError.NoError): QuicError =
        (_state.value as? QuicConnectionState.Closed)?.error ?: fallback
}

/**
 * A [ByteStream] over an OS-26 [NWQuic26Stream]. Zero-copy both ways (NSData ↔ [NSDataBuffer] /
 * `dispatch_data_t`). Half-close ([shutdownSend]) sends an empty FINAL message; [reset] aborts with an
 * application error code (RESET_STREAM / STOP_SENDING). Mirrors [NWQuicByteStream] but over the new API
 * (which exposes a per-stream `endOfStream` flag and application error code directly).
 */
internal class NWQuic26ByteStream(
    private val stream: NWQuic26Stream,
    private val isConnectionLive: () -> Boolean,
) : ByteStream,
    HalfCloseable,
    Resettable {
    private val lifecycle = AtomicInt(LIFECYCLE_OPEN)

    override val isOpen: Boolean get() = lifecycle.value != LIFECYCLE_CLOSED

    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)

    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

    override suspend fun read(deadline: Duration): ReadResult =
        withTimeout(deadline) {
            suspendCancellableCoroutine { cont ->
                stream.receiveWithMaxBytes(STREAM_READ_MAX) { data, _, resetCode, _, _ ->
                    when {
                        data != null && nsDataLengthInt(data) > 0 -> {
                            if (cont.isActive) cont.resume(ReadResult.Data(nsDataToReadBuffer(data)))
                        }
                        // resetCode != UInt64.MAX means the peer sent RESET_STREAM (carries the app code);
                        // otherwise a clean end-of-stream (FIN) or transport close.
                        resetCode != ULong.MAX_VALUE -> if (cont.isActive) cont.resume(ReadResult.Reset)
                        else -> if (cont.isActive) cont.resume(ReadResult.End)
                    }
                }
            }
        }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten =
        withTimeout(deadline) {
            check(lifecycle.value == LIFECYCLE_OPEN) { "NWQuic26ByteStream send side is finished" }
            val remaining = buffer.remaining()
            val nsData = buffer.toNativeData().nsData
            suspendCancellableCoroutine { cont ->
                stream.send(nsData, endOfStream = false) { errCode, resetCode, desc ->
                    if (errCode != 0) {
                        if (cont.isActive) cont.resumeWithException(writeError(resetCode, errCode, desc))
                    } else if (cont.isActive) {
                        cont.resume(BytesWritten(remaining))
                    }
                }
            }
        }

    /**
     * Classify a non-zero send error: a peer STOP_SENDING/RESET_STREAM (carries the stream's QUIC
     * application error code in [resetCode], else [ULong.MAX_VALUE]) is a stream-scoped
     * [QuicStreamException] — the connection stays usable — whereas anything else is a connection-level
     * [QuicCloseException]. NW reports both a peer stream reset AND a connection close as POSIX 57, so the
     * stream application error code is the only reliable discriminator (mirrors [NWQuicByteStream], issue
     * #134). The WebTransport layer decodes the [QuicStreamAbort.StopSending] code back to its app code.
     */
    private fun writeError(
        resetCode: ULong,
        errCode: Int,
        desc: String?,
    ): Throwable =
        when {
            resetCode != ULong.MAX_VALUE ->
                // NW resolved the peer's stream application error code → unambiguously a stream-scoped
                // abort (STOP_SENDING on our write path, per QuicStreamAbort's KDoc).
                QuicStreamException(
                    stream.streamId().toLong(),
                    QuicStreamAbort.StopSending(resetCode.toLong()),
                    "QUIC stream ${stream.streamId()} reset by peer (application error $resetCode)",
                )
            isConnectionLive() ->
                // No per-stream code, but the connection is still up. NW collapses a peer
                // STOP_SENDING/RESET and a true connection close to the SAME POSIX 57 (issue #134); since
                // the connection remains live, this is a STREAM-scoped abort whose code NW didn't surface
                // — NOT a connection close. Mapping it to QuicCloseException would tear down a healthy
                // connection (the exact anti-pattern QuicStreamException's KDoc warns against) and diverge
                // from quiche, which always reports a peer reset as a QuicStreamException. Report it as a
                // stream abort with the direction/code unresolved so callers abandon just this stream and
                // the connection keeps serving its others (PR #176 macos-26 loopback race).
                QuicStreamException(
                    stream.streamId().toLong(),
                    QuicStreamAbort.Unspecified(0L),
                    "QUIC stream ${stream.streamId()} aborted (POSIX $errCode; peer application code unavailable) ${desc ?: ""}",
                )
            else ->
                // The connection itself is gone — a genuine connection-level failure.
                QuicCloseException(
                    QuicError.InternalError("QUIC write error: $errCode"),
                    "QUIC write error: $errCode ${desc ?: ""}",
                )
        }

    override suspend fun shutdownSend() {
        if (!lifecycle.compareAndSet(LIFECYCLE_OPEN, LIFECYCLE_SEND_FINISHED)) return
        sendFin()
    }

    override suspend fun close() {
        when {
            lifecycle.compareAndSet(LIFECYCLE_OPEN, LIFECYCLE_CLOSED) -> sendFin()
            lifecycle.compareAndSet(LIFECYCLE_SEND_FINISHED, LIFECYCLE_CLOSED) -> {}
            else -> {}
        }
    }

    override suspend fun reset(errorCode: Long) {
        if (lifecycle.getAndSet(LIFECYCLE_CLOSED) == LIFECYCLE_CLOSED) return
        stream.resetWithAppErrorCode(errorCode.toULong())
    }

    /** Send the stream FIN: an empty FINAL message (the new API's `endOfStream=true`). */
    private suspend fun sendFin() {
        kotlinx.coroutines.withTimeoutOrNull(FIN_TIMEOUT) {
            suspendCancellableCoroutine { cont ->
                stream.send(NSData(), endOfStream = true) { _, _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private companion object {
        private const val LIFECYCLE_OPEN = 0
        private const val LIFECYCLE_SEND_FINISHED = 1
        private const val LIFECYCLE_CLOSED = 2
        private const val STREAM_READ_MAX = 65_536
        private val FIN_TIMEOUT: Duration = 5.seconds
    }
}

/**
 * Build + bind an OS-26 Swift-API QUIC server. Sibling to [buildAppleQuicServer] (the legacy group
 * listener). The shim imports the PKCS#12 identity itself; each accepted connection is wired (inbound
 * stream/datagram handlers SET synchronously, which drives its handshake) and delivered to
 * [connections] as a ready-to-await [AppleQuicSwiftConnection].
 */
internal suspend fun buildAppleQuicSwiftServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration,
): QuicServer {
    val p12Path =
        tlsConfig.pkcs12Path
            ?: throw IllegalArgumentException(
                "Apple QUIC server requires QuicTlsConfig.pkcs12Path — Network.framework needs a sec_identity_t.",
            )
    val datagramsEnabled = quicOptions.datagrams != null
    val acceptedConns = Channel<AppleQuicSwiftConnection>(Channel.UNLIMITED)
    val boundPort = CompletableDeferred<Int>()

    val bridge = NWQuic26Bridge()
    val listener =
        bridge.listenWithHost(
            host = host,
            port = port.toUShort(),
            alpn = quicOptions.alpnProtocols,
            p12Path = p12Path,
            p12Password = tlsConfig.pkcs12Password ?: "",
            idleTimeoutMs =
                quicOptions.idleTimeout.inWholeMilliseconds
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
            maxDatagramFrameSize = if (datagramsEnabled) DATAGRAM_FRAME_SIZE_MAX.toInt() else 0,
            keepAliveMs =
                quicOptions.keepAliveInterval
                    ?.inWholeMilliseconds
                    ?.coerceAtMost(Int.MAX_VALUE.toLong())
                    ?.toInt() ?: 0,
            onConnection = { rawConn ->
                // Runs on the shim's Swift thread synchronously. Wire everything NOW (setting the inbound
                // handlers drives the server handshake — a passive wait would stall it), then deliver a
                // ready-to-await connection. The handshake completes asynchronously; connections() awaits
                // each conn's `established` before running the user handler.
                val conn = rawConn!!
                val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
                val datagramChannel = if (datagramsEnabled) Channel<ReadBuffer>(Channel.UNLIMITED) else null
                val swiftConn =
                    AppleQuicSwiftConnection(
                        incomingStreams = incomingStreams,
                        datagramChannel = datagramChannel,
                        bufferFactory = BufferFactory.network(),
                        datagramsEnabled = datagramsEnabled,
                        negotiatedAlpn = quicOptions.alpnProtocols.firstOrNull() ?: "",
                    )
                swiftConn.attach(conn, host ?: "::", port)
                wireInbound(conn, incomingStreams, datagramChannel, tag = "srv", isConnectionLive = swiftConn::connectionLive)
                acceptedConns.trySend(swiftConn)
            },
            onListenerState = { errCode, listenerPort, desc ->
                if (errCode == 0) {
                    boundPort.complete(listenerPort.toInt())
                } else if (!boundPort.isCompleted) {
                    boundPort.completeExceptionally(
                        SocketConnectionException.Refused(
                            host ?: "::",
                            port,
                            platformError = "QUIC listener failed: code=$errCode ${desc ?: ""}",
                        ),
                    )
                }
            },
        )

    val resolvedPort = withTimeout(timeout) { boundPort.await() }
    return AppleQuicSwiftServer(listener, resolvedPort, acceptedConns)
}

/** The OS-26 Swift-API QUIC server. Accepted connections arrive pre-wired over [acceptedConns]. */
private class AppleQuicSwiftServer(
    private val listener: NWQuic26Listener,
    private val boundPort: Int,
    private val acceptedConns: Channel<AppleQuicSwiftConnection>,
) : QuicServer {
    override val port: Int get() = boundPort

    @Volatile
    private var closed = false

    override suspend fun connections(handler: suspend QuicScope.() -> Unit) =
        coroutineScope {
            for (conn in acceptedConns) {
                launch {
                    // Wait for the handshake; skip connections that failed before ready.
                    val ready =
                        try {
                            conn.established.await()
                            true
                        } catch (_: Throwable) {
                            false
                        }
                    if (!ready) {
                        conn.close()
                        return@launch
                    }
                    // Resolve the datagram channel before the handler runs so maxDatagramSize() is
                    // accurate server-side too (mirrors the client connect path).
                    conn.ensureDatagramsReady()
                    try {
                        conn.handler()
                    } finally {
                        conn.close()
                    }
                }
            }
        }

    override suspend fun close() {
        if (closed) return
        closed = true
        listener.cancel()
        acceptedConns.close()
    }
}
