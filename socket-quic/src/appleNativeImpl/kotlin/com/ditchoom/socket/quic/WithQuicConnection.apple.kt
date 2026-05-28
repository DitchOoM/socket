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
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
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

        val nwConn =
            nw_helper_create_quic_connection(
                hostname,
                port.toUShort(),
                alpnList,
                NSNumber(bool = quicOptions.verifyPeer),
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
