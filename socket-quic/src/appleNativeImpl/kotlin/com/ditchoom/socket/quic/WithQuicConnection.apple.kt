@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.quic.nwhelpers.nw_helper_create_quic_connection
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_receive
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_send
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_start
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Network.nw_connection_t
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * Apple [withQuicConnection] using Network.framework.
 *
 * Zero-copy read path: Network.framework → dispatch_data_t → NSData → NSDataBuffer (no copy)
 * Zero-copy write path: NSDataBuffer → toNSData() → dispatch_data_t (no copy for NSData-backed buffers)
 *
 * Requires iOS 15+ / macOS 12+.
 */
actual suspend fun <R> withQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: ConnectionOptions,
    timeout: Duration,
    block: suspend QuicScope.() -> R,
): R =
    withTimeout(timeout) {
        // Build ALPN array — pass as List which K/N bridges to NSArray
        val alpnList: List<Any?> = quicOptions.alpnProtocols

        // Load pinned CA cert (PEM file → DER bytes → NSData) when the
        // caller opted in. nw_helper_create_quic_connection installs a
        // proper SecTrustEvaluateWithError-based verify_block when
        // trustedCaDer != nil — see nw_quic_helpers.h for the full
        // rationale (and PR #54 iter 1-5 for why the previous
        // complete(true)-no-evaluation bypass crashed K/N).
        println("[kt withQuicConnection] before loadPemCertAsDer path=${quicOptions.pinnedCaCertPath ?: "<null>"}")
        val pinnedCaDer: NSData? = quicOptions.pinnedCaCertPath?.let { loadPemCertAsDer(it) }
        println("[kt withQuicConnection] after loadPemCertAsDer pinnedCaDer.length=${pinnedCaDer?.length ?: 0u}")

        val nwConn =
            nw_helper_create_quic_connection(
                hostname,
                port.toUShort(),
                alpnList,
                NSNumber(bool = quicOptions.verifyPeer),
                pinnedCaDer,
                quicOptions.idleTimeout.inWholeSeconds.toInt(),
                timeout.inWholeSeconds.toInt(),
            ) ?: throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create QUIC connection")

        // Wait for handshake completion
        suspendCancellableCoroutine { cont ->
            nw_helper_quic_set_state_handler(nwConn) { state, _, errorCode, errorDesc ->
                when (state) {
                    3 -> { // ready
                        if (cont.isActive) cont.resume(Unit)
                    }
                    4 -> { // failed
                        if (cont.isActive) {
                            cont.resumeWithException(
                                SocketConnectionException.Refused(
                                    hostname,
                                    port,
                                    platformError = "QUIC handshake failed: code=$errorCode ${errorDesc ?: ""}",
                                ),
                            )
                        }
                    }
                    5 -> { // cancelled
                        if (cont.isActive) {
                            cont.resumeWithException(
                                SocketClosedException.General("QUIC connection cancelled"),
                            )
                        }
                    }
                    else -> {} // waiting=1, preparing=2 — in progress
                }
            }

            nw_helper_quic_start(nwConn)

            cont.invokeOnCancellation {
                nw_helper_quic_cancel(nwConn)
            }
        }

        val quicConn = AppleQuicConnection(nwConn, connectionOptions.bufferFactory)
        try {
            quicConn.block()
        } finally {
            quicConn.close()
        }
    }

/**
 * Apple QUIC connection wrapping an NWConnection.
 *
 * Network.framework handles QUIC multiplexing internally — the same
 * NWConnection API used for TCP works identically for QUIC.
 */
private class AppleQuicConnection(
    private val nwConn: nw_connection_t,
    private val bufferFactory: BufferFactory,
    private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
) : QuicConnection,
    CoroutineScope by scope {
    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Established("h3"))
    override val state: StateFlow<QuicConnectionState> = _state

    private val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    private val nextClientStreamId = kotlin.concurrent.AtomicLong(0L)

    @Volatile
    private var closed = false

    override suspend fun openStream(): QuicByteStream {
        check(!closed) { "AppleQuicConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        val streamId = QuicStreamId(nextClientStreamId.getAndAdd(4))
        return QuicByteStream(streamId, NWQuicByteStream(nwConn))
    }

    override suspend fun acceptStream(): QuicByteStream {
        check(!closed) { "AppleQuicConnection is closed" }
        return incomingStreams.receive()
    }

    override fun streams(): Flow<QuicByteStream> = incomingStreams.consumeAsFlow()

    override suspend fun close(error: QuicError) {
        if (closed) return
        closed = true
        _state.value = QuicConnectionState.Draining
        nw_helper_quic_cancel(nwConn)
        incomingStreams.close()
        _state.value = QuicConnectionState.Closed(error)
    }
}

/**
 * ByteStream backed by a Network.framework NWConnection QUIC stream.
 *
 * Zero-copy read: NSData from receive callback → NSDataBuffer (wraps, no copy)
 * Zero-copy write: NSDataBuffer.data → dispatch_data_t (wraps, no copy)
 */
private class NWQuicByteStream(
    private val nwConn: nw_connection_t,
) : ByteStream {
    @Volatile
    private var streamClosed = false

    override val isOpen: Boolean get() = !streamClosed

    override suspend fun read(timeout: Duration): ReadResult =
        withTimeout(timeout) {
            suspendCancellableCoroutine { cont ->
                nw_helper_quic_receive(nwConn, 1u, 65536u) { data, isComplete, _, errorCode, _ ->
                    when {
                        data != null && data.length.toInt() > 0 -> {
                            // Zero-copy: wrap NSData directly
                            val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                            buffer.position(data.length.toInt())
                            if (cont.isActive) cont.resume(ReadResult.Data(buffer))
                        }
                        isComplete?.boolValue == true -> {
                            if (cont.isActive) cont.resume(ReadResult.End)
                        }
                        errorCode != 0 -> {
                            if (cont.isActive) cont.resume(ReadResult.Reset)
                        }
                        else -> {
                            if (cont.isActive) cont.resume(ReadResult.End)
                        }
                    }
                }
            }
        }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten =
        withTimeout(timeout) {
            val remaining = buffer.remaining()
            val nsData = buffer.toNSData()

            suspendCancellableCoroutine { cont ->
                nw_helper_quic_send(nwConn, nsData, NSNumber(bool = false)) { _, errorCode, _ ->
                    if (errorCode != 0) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                SocketClosedException.General("QUIC write error: $errorCode"),
                            )
                        }
                    } else {
                        if (cont.isActive) cont.resume(BytesWritten(remaining))
                    }
                }
            }
        }

    override suspend fun close() {
        if (streamClosed) return
        streamClosed = true
        // Send FIN: empty data with is_complete=true
        suspendCancellableCoroutine { cont ->
            val emptyData = NSData()
            nw_helper_quic_send(nwConn, emptyData, NSNumber(bool = true)) { _, _, _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}

/**
 * Convert ReadBuffer to NSData for sending via Network.framework.
 *
 * Zero-copy paths:
 * - NSDataBuffer: returns underlying NSData directly
 * - NativeMemoryAccess (deterministic buffers): NSData wraps native address (no copy)
 *
 * The caller's buffer must outlive the NSData (Network.framework copies internally on send).
 */
private fun ReadBuffer.toNSData(): NSData {
    // Fast path: already backed by NSData
    if (this is NSDataBuffer) return data

    // Zero-copy path: use nativeMemoryAccess address directly
    val nma = this.nativeMemoryAccess
    if (nma != null) {
        val addr = (nma.nativeAddress + position().toLong()).toCPointer<kotlinx.cinterop.ByteVar>()
        if (addr != null) {
            // bytesNoCopy: NSData wraps our buffer's memory without copying.
            // freeWhenDone=false: we manage the buffer lifecycle, not NSData.
            return NSData.create(bytesNoCopy = addr, length = remaining().convert(), freeWhenDone = false)
        }
    }

    // Last resort: empty data
    return NSData()
}

/**
 * Read a PEM-encoded certificate file from disk and decode the body to
 * DER bytes wrapped in an [NSData].
 *
 * Used to build the pinned CA payload that flows into
 * `nw_helper_create_quic_connection(... trusted_ca_der ...)` —
 * `SecCertificateCreateWithData` on the C side wants raw DER.
 *
 * Returns null if the file is missing or doesn't look like a PEM
 * certificate; the C helper interprets nil as "no pinning, use system
 * trust" so the connection still attempts.
 *
 * - Uses `kotlin.io.encoding.Base64` (Kotlin 2.x) for the body decode —
 *   no need for cinterop into a base64 lib.
 * - Reads via `NSString.stringWithContentsOfFile` to leverage the
 *   Foundation file API (works across all Apple targets, including
 *   iOS simulator where direct POSIX file paths can be sandboxed).
 * - Strips PEM markers + whitespace before decoding so multi-line
 *   wrapping (the standard 64-col wrap) is tolerated.
 *
 * One-line @Suppress("NoByteArrayInProd") follows the platform-boundary
 * rule in CLAUDE.md: this is the cinterop seam between Kotlin's Base64
 * (ByteArray output) and Foundation's NSData (the actual API surface
 * the C helper consumes). Not on a hot path; allocated once per
 * connection at handshake time.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun loadPemCertAsDer(path: String): NSData? {
    val pem =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            ?: return null
    val pemStr = pem.toString()

    val begin = pemStr.indexOf("-----BEGIN CERTIFICATE-----")
    if (begin < 0) return null
    val bodyStart = pemStr.indexOf('\n', begin).takeIf { it >= 0 }?.plus(1) ?: return null
    val end = pemStr.indexOf("-----END CERTIFICATE-----", bodyStart)
    if (end < 0) return null

    val base64Body =
        pemStr
            .substring(bodyStart, end)
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")

    @Suppress("NoByteArrayInProd") // platform boundary: Base64.decode → NSData wrapping for Network.framework
    val derBytes: ByteArray =
        try {
            Base64.decode(base64Body)
        } catch (_: IllegalArgumentException) {
            return null
        }

    return derBytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = derBytes.size.convert())
    }
}
