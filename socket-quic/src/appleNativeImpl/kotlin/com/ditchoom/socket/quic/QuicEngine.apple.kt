package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.quic.nwhelpers.nw_helper_create_quic_connection
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_receive
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_send
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_start
import com.ditchoom.socket.transport.ByteStream
import com.ditchoom.socket.transport.BytesWritten
import com.ditchoom.socket.transport.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Network.nw_connection_t
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

actual fun defaultQuicEngine(): QuicEngine = AppleQuicEngine()

/**
 * Apple QUIC engine using Network.framework.
 *
 * Zero-copy: Network.framework delivers data as NSData (dispatch_data_t),
 * which is wrapped in [NSDataBuffer] without copying. Sends accept NSData
 * directly from [ReadBuffer.toNSData].
 *
 * iOS 15+ / macOS 12+ required for QUIC.
 */
@OptIn(ExperimentalForeignApi::class)
private class AppleQuicEngine : QuicEngine {
    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
    ): QuicConnection =
        withTimeout(timeout) {
            val alpnArray = quicOptions.alpnProtocols.map { it as Any } as List<String>

            val nwConn = nw_helper_create_quic_connection(
                hostname,
                port.toUShort(),
                alpnArray as platform.Foundation.NSArray,
                NSNumber(bool = quicOptions.verifyPeer),
                quicOptions.idleTimeout.inWholeSeconds.toInt(),
                timeout.inWholeSeconds.toInt(),
            ) ?: throw SocketConnectionException.Refused("Failed to create QUIC connection to $hostname:$port")

            // Wait for connection ready
            suspendCancellableCoroutine { cont ->
                nw_helper_quic_set_state_handler(nwConn) { state, errorDomain, errorCode, errorDesc ->
                    when (state) {
                        3 -> { // ready
                            if (cont.isActive) {
                                cont.resume(Unit)
                            }
                        }
                        4 -> { // failed
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    SocketConnectionException.Refused(
                                        "QUIC connection failed: domain=$errorDomain code=$errorCode ${errorDesc ?: ""}",
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
                        else -> {} // waiting, preparing — ignore
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
 * Apple QUIC connection wrapping an [nw_connection_t].
 *
 * Network.framework handles QUIC internally — the same NWConnection API
 * used for TCP works for QUIC. Streams are multiplexed by the framework.
 *
 * Zero-copy reads: NSData from receive callback → [NSDataBuffer] (no copy).
 * Zero-copy writes: [ReadBuffer.toNSData] → dispatch_data_t (no copy for NSDataBuffer).
 */
@OptIn(ExperimentalForeignApi::class)
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
        val byteStream = NWQuicByteStream(nwConn, bufferFactory)
        return QuicByteStream(streamId, byteStream)
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
 * [ByteStream] backed by an NWConnection QUIC stream.
 *
 * Zero-copy read path:
 *   Network.framework → NSData (dispatch_data_t) → NSDataBuffer (no copy)
 *
 * Zero-copy write path:
 *   NSDataBuffer → toNSData() → dispatch_data_t (no copy)
 *   Other buffer types → copy to NSData (unavoidable)
 */
@OptIn(ExperimentalForeignApi::class)
private class NWQuicByteStream(
    private val nwConn: nw_connection_t,
    private val bufferFactory: BufferFactory,
) : ByteStream {
    override val isOpen: Boolean get() = true // NWConnection manages state

    override suspend fun read(timeout: Duration): ReadResult =
        withTimeout(timeout) {
            suspendCancellableCoroutine { cont ->
                nw_helper_quic_receive(nwConn, 1u, 65536u) { data, isComplete, errorDomain, errorCode, errorDesc ->
                    when {
                        data != null && data.length.toInt() > 0 -> {
                            val buffer = NSDataBuffer(data, com.ditchoom.buffer.ByteOrder.BIG_ENDIAN)
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
                nw_helper_quic_send(nwConn, nsData, NSNumber(bool = false)) { errorDomain, errorCode, errorDesc ->
                    if (errorCode != 0) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                SocketClosedException.General("QUIC write error: domain=$errorDomain code=$errorCode"),
                            )
                        }
                    } else {
                        if (cont.isActive) cont.resume(BytesWritten(remaining))
                    }
                }
            }
        }

    override suspend fun close() {
        // Send FIN by writing empty data with is_complete=true
        suspendCancellableCoroutine { cont ->
            nw_helper_quic_send(nwConn, NSData(), NSNumber(bool = true)) { _, _, _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}

/** Convert ReadBuffer to NSData for sending. Zero-copy for NSDataBuffer. */
@OptIn(ExperimentalForeignApi::class)
private fun ReadBuffer.toNSData(): NSData {
    return when (this) {
        is NSDataBuffer -> data
        else -> {
            val bytes = readByteArray(remaining())
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <R> ByteArray.usePinned(block: (kotlinx.cinterop.Pinned<ByteArray>) -> R): R {
    val pinned = kotlinx.cinterop.pin()
    try {
        return block(pinned)
    } finally {
        pinned.unpin()
    }
}

private fun ByteArray.pin() = kotlinx.cinterop.pin(this)

private fun kotlinx.cinterop.pin(array: ByteArray): kotlinx.cinterop.Pinned<ByteArray> {
    return array.let { kotlinx.cinterop.pin(it) }
}
