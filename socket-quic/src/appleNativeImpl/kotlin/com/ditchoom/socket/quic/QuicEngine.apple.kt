@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.quic.nwhelpers.nw_helper_create_quic_connection
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_receive
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_send
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_start
import com.ditchoom.socket.transport.ByteStream
import com.ditchoom.socket.transport.BytesWritten
import com.ditchoom.socket.transport.ReadResult
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSMutableArray
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Network.nw_connection_t
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = AppleQuicEngine()

/**
 * Apple QUIC engine using Network.framework.
 *
 * Zero-copy read path: Network.framework → dispatch_data_t → NSData → NSDataBuffer (no copy)
 * Zero-copy write path: NSDataBuffer → toNSData() → dispatch_data_t (no copy for NSData-backed buffers)
 *
 * Requires iOS 15+ / macOS 12+.
 */
private class AppleQuicEngine : QuicEngine {
    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
    ): QuicConnection =
        withTimeout(timeout) {
            // Build ALPN array
            val alpnArray = NSMutableArray()
            quicOptions.alpnProtocols.forEach { alpnArray.addObject(it) }

            val nwConn = nw_helper_create_quic_connection(
                hostname,
                port.toUShort(),
                alpnArray,
                NSNumber(bool = quicOptions.verifyPeer),
                quicOptions.idleTimeout.inWholeSeconds.toInt(),
                timeout.inWholeSeconds.toInt(),
            ) ?: throw SocketConnectionException.Refused("Failed to create QUIC connection to $hostname:$port")

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
                                        "QUIC handshake failed: code=$errorCode ${errorDesc ?: ""}",
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

            AppleQuicConnection(nwConn, connectionOptions.bufferFactory)
        }

    override fun close() {}
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
) : QuicConnection {
    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Established("h3"))
    override val state: StateFlow<QuicConnectionState> = _state

    private val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    private var nextClientStreamId = 0L
    private var closed = false

    override suspend fun openStream(): QuicByteStream {
        check(!closed) { "AppleQuicConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        val streamId = QuicStreamId(nextClientStreamId)
        nextClientStreamId += 4
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
            val emptyData = NSData.create(length = 0u)
            nw_helper_quic_send(nwConn, emptyData, NSNumber(bool = true)) { _, _, _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}

/**
 * Convert ReadBuffer to NSData for sending via Network.framework.
 * Zero-copy for NSDataBuffer (returns underlying NSData directly).
 * Copy for other buffer types (writes to pinned ByteArray → NSData).
 */
private fun ReadBuffer.toNSData(): NSData =
    when (this) {
        is NSDataBuffer -> data
        else -> {
            val bytes = readByteArray(remaining())
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
            }
        }
    }
