package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.transport.ReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.channels.DatagramChannel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * JVM QUIC connection backed by quiche + [DatagramChannel].
 *
 * Zero-copy data path:
 *   DatagramChannel.read(directByteBuffer) → nativeAddress → quiche_conn_recv()
 *   quiche_conn_stream_recv(nativeAddress) → app reads from same buffer
 *   quiche_conn_send(nativeAddress) → DatagramChannel.write(directByteBuffer)
 */
class JvmQuicConnection internal constructor(
    private val api: QuicheApi,
    private val conn: QuicheConn,
    private val channel: DatagramChannel,
    private val bufferFactory: BufferFactory,
    private val scope: CoroutineScope,
) : QuicConnection,
    QuicheStreamAdapter {
    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)
    override val state: StateFlow<QuicConnectionState> = _state

    private val connMutex = Mutex()
    private val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    private var nextClientStreamId = 0L
    private var closed = false

    private val udpRecvBuf: PlatformBuffer = bufferFactory.allocate(MAX_DATAGRAM_SIZE)
    private val udpSendBuf: PlatformBuffer = bufferFactory.allocate(MAX_DATAGRAM_SIZE)

    // recvInfo/sendInfo structs allocated as native buffers (reused per event loop iteration)
    // quiche_recv_info: from(ptr+len) + to(ptr+len) = ~32 bytes
    // quiche_send_info: from(sockaddr_storage 128 + len) + to(sockaddr_storage 128 + len) + timespec = ~280 bytes
    private val recvInfoBuf: PlatformBuffer = bufferFactory.allocate(RECV_INFO_SIZE)
    private val sendInfoBuf: PlatformBuffer = bufferFactory.allocate(SEND_INFO_SIZE)

    private var eventLoopJob: Job? = null

    internal fun start() {
        eventLoopJob = scope.launch(Dispatchers.IO) { eventLoop() }
    }

    internal suspend fun awaitEstablished(timeout: Duration) {
        withTimeout(timeout) {
            while (_state.value is QuicConnectionState.Handshaking) {
                delay(1.milliseconds)
            }
        }
    }

    private suspend fun eventLoop() {
        val recvByteBuffer = (udpRecvBuf.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
        val recvInfoAddr = recvInfoBuf.nativeMemoryAccess!!.nativeAddress.toLong()
        val sendInfoAddr = sendInfoBuf.nativeMemoryAccess!!.nativeAddress.toLong()

        while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive != false && !closed) {
            // 1. Receive UDP packet → feed to quiche
            recvByteBuffer.clear()
            val received =
                try {
                    channel.read(recvByteBuffer)
                } catch (_: Exception) {
                    if (closed) break
                    -1
                }

            if (received > 0) {
                connMutex.withLock {
                    val recvAddr = udpRecvBuf.nativeMemoryAccess!!.nativeAddress.toLong()
                    // recvInfoBuf is pre-zeroed — from/to sockaddrs are set by caller
                    // For connected UDP, the kernel fills the source already
                    api.connRecv(conn, recvAddr, received, QuicheRecvInfo(recvInfoAddr))
                }
            }

            // 2. Update connection state
            connMutex.withLock {
                if (api.connIsEstablished(conn) && _state.value is QuicConnectionState.Handshaking) {
                    _state.value = QuicConnectionState.Established("h3")
                }
                if (api.connIsClosed(conn)) {
                    _state.value = QuicConnectionState.Closed(null)
                    break
                }
            }

            // 3. Flush outgoing packets
            flushOutgoing(sendInfoAddr)

            // 4. Handle quiche timeout
            connMutex.withLock {
                val timeoutNanos = api.connTimeoutAsNanos(conn)
                if (timeoutNanos == 0L) {
                    api.connOnTimeout(conn)
                }
            }

            if (received <= 0) delay(1.milliseconds)
        }
    }

    private suspend fun flushOutgoing(sendInfoAddr: Long) {
        connMutex.withLock {
            val sendAddr = udpSendBuf.nativeMemoryAccess!!.nativeAddress.toLong()
            val sendByteBuffer = (udpSendBuf.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer

            while (true) {
                val written = api.connSend(conn, sendAddr, MAX_DATAGRAM_SIZE, QuicheSendInfo(sendInfoAddr))
                if (written <= 0) break

                sendByteBuffer.clear()
                sendByteBuffer.limit(written)
                channel.write(sendByteBuffer)
            }
        }
    }

    // --- QuicConnection ---

    override suspend fun openStream(): QuicByteStream {
        check(!closed) { "JvmQuicConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        val streamId = QuicStreamId(nextClientStreamId)
        nextClientStreamId += 4
        return QuicByteStream(streamId, QuicheStreamByteStream(streamId, this, bufferFactory))
    }

    override suspend fun acceptStream(): QuicByteStream {
        check(!closed) { "JvmQuicConnection is closed" }
        return incomingStreams.receive()
    }

    override fun streams(): Flow<QuicByteStream> = incomingStreams.consumeAsFlow()

    override suspend fun close(error: QuicError) {
        if (closed) return
        closed = true
        _state.value = QuicConnectionState.Draining

        connMutex.withLock {
            api.connClose(conn, error is QuicError.ApplicationError, error.code, 0L, 0)
        }
        val sendInfoAddr = sendInfoBuf.nativeMemoryAccess!!.nativeAddress.toLong()
        flushOutgoing(sendInfoAddr)

        eventLoopJob?.cancel()
        channel.close()
        api.connFree(conn)
        udpRecvBuf.freeNativeMemory()
        udpSendBuf.freeNativeMemory()
        recvInfoBuf.freeNativeMemory()
        sendInfoBuf.freeNativeMemory()
        incomingStreams.close()

        _state.value = QuicConnectionState.Closed(error)
    }

    // --- QuicheStreamAdapter: zero-copy stream I/O ---

    override suspend fun streamRead(
        streamId: QuicStreamId,
        bufferFactory: BufferFactory,
        bufferSize: Int,
        timeout: Duration,
    ): ReadResult {
        val buffer = bufferFactory.allocate(bufferSize)
        val addr = buffer.nativeMemoryAccess!!.nativeAddress.toLong()

        val result =
            withTimeout(timeout) {
                connMutex.withLock {
                    api.connStreamRecv(conn, streamId.id, addr, bufferSize)
                }
            }

        if (result < 0) {
            buffer.freeNativeMemory()
            return ReadResult.End
        }

        val bytesRead = (result and 0x7FFFFFFFL).toInt()
        val fin = result and (1L shl 63) != 0L

        if (bytesRead == 0 && fin) {
            buffer.freeNativeMemory()
            return ReadResult.End
        }

        buffer.position(bytesRead)
        buffer.resetForRead()
        return ReadResult.Data(buffer)
    }

    override suspend fun streamWrite(
        streamId: QuicStreamId,
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val addr = buffer.nativeMemoryAccess!!.nativeAddress.toLong() + buffer.position()
        val remaining = buffer.remaining()

        val written =
            withTimeout(timeout) {
                connMutex.withLock {
                    api.connStreamSend(conn, streamId.id, addr, remaining, false)
                }
            }

        if (written < 0) {
            throw SocketClosedException.General("quiche stream write error: $written")
        }

        val sendInfoAddr = sendInfoBuf.nativeMemoryAccess!!.nativeAddress.toLong()
        flushOutgoing(sendInfoAddr)

        return written
    }

    override suspend fun streamClose(streamId: QuicStreamId) {
        connMutex.withLock {
            api.connStreamSend(conn, streamId.id, 0L, 0, true)
        }
        val sendInfoAddr = sendInfoBuf.nativeMemoryAccess!!.nativeAddress.toLong()
        flushOutgoing(sendInfoAddr)
    }

    companion object {
        private const val MAX_DATAGRAM_SIZE = 1350

        // Conservative sizes for quiche struct allocation
        private const val RECV_INFO_SIZE = 64 // quiche_recv_info: 2x(sockaddr* + socklen_t)
        private const val SEND_INFO_SIZE = 512 // quiche_send_info: 2x(sockaddr_storage + socklen_t) + timespec
    }
}
