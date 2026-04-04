package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
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
 * Lifecycle:
 * 1. Opens a UDP [DatagramChannel] and connects to the peer
 * 2. Creates a quiche connection via [QuicheApi.connect]
 * 3. Runs an event loop that:
 *    - Reads UDP packets from the channel → feeds to quiche
 *    - Flushes outgoing quiche packets → sends via channel
 *    - Handles quiche timeouts
 * 4. Stream reads/writes go through the event loop which calls
 *    `conn_stream_recv` / `conn_stream_send` with native buffer addresses
 *
 * Zero-copy: all buffer addresses come from [BufferFactory.deterministic] and
 * are passed to quiche via [QuicheApi] (FFM on JDK 21+, JNI fallback).
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

    // UDP I/O buffers (reused across the event loop)
    private val udpRecvBuf: PlatformBuffer = bufferFactory.allocate(MAX_DATAGRAM_SIZE)
    private val udpSendBuf: PlatformBuffer = bufferFactory.allocate(MAX_DATAGRAM_SIZE)

    private var eventLoopJob: Job? = null

    /** Start the event loop. Called after construction by [JvmQuicEngine]. */
    internal fun start() {
        eventLoopJob = scope.launch(Dispatchers.IO) { eventLoop() }
    }

    private suspend fun eventLoop() {
        val recvByteBuffer = (udpRecvBuf.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer

        while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive != false && !closed) {
            // 1. Receive UDP packets and feed to quiche
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
                    // TODO: create proper quiche_recv_info with sockaddr
                    // For now this is the data path skeleton
                    api.connRecv(conn, recvAddr, received, 0L.let { TODO("recvInfo allocation") })
                }
            }

            // 2. Check connection state
            connMutex.withLock {
                if (api.connIsEstablished(conn) && _state.value is QuicConnectionState.Handshaking) {
                    _state.value = QuicConnectionState.Established("h3") // TODO: read negotiated ALPN
                }
                if (api.connIsClosed(conn)) {
                    _state.value = QuicConnectionState.Closed(null) // TODO: read error
                    break
                }
            }

            // 3. Flush outgoing packets
            flushOutgoing()

            // 4. Handle quiche timeout
            connMutex.withLock {
                val timeoutNanos = api.connTimeoutAsNanos(conn)
                if (timeoutNanos == 0L) {
                    api.connOnTimeout(conn)
                }
            }

            // Yield to avoid busy-spinning when no data
            if (received <= 0) {
                delay(1.milliseconds)
            }
        }
    }

    private suspend fun flushOutgoing() {
        connMutex.withLock {
            val sendAddr = udpSendBuf.nativeMemoryAccess!!.nativeAddress.toLong()
            while (true) {
                val sendInfoPtr = TODO("sendInfo allocation") as Long
                val written = api.connSend(conn, sendAddr, MAX_DATAGRAM_SIZE, QuicheSendInfo(sendInfoPtr))
                if (written <= 0) break

                val sendByteBuffer = (udpSendBuf.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
                sendByteBuffer.position(0).limit(written)
                channel.write(sendByteBuffer)
            }
        }
    }

    // --- QuicConnection interface ---

    override suspend fun openStream(): QuicByteStream {
        check(!closed) { "JvmQuicConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        val streamId = QuicStreamId(nextClientStreamId)
        nextClientStreamId += 4
        val byteStream = QuicheStreamByteStream(streamId, this, bufferFactory)
        return QuicByteStream(streamId, byteStream)
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
        flushOutgoing()

        eventLoopJob?.cancel()
        channel.close()
        api.connFree(conn)
        udpRecvBuf.freeNativeMemory()
        udpSendBuf.freeNativeMemory()
        incomingStreams.close()

        _state.value = QuicConnectionState.Closed(error)
    }

    // --- QuicheStreamAdapter (zero-copy stream I/O) ---

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

        // Flush outgoing packets after write
        flushOutgoing()

        return written
    }

    override suspend fun streamClose(streamId: QuicStreamId) {
        connMutex.withLock {
            // Send empty write with FIN
            api.connStreamSend(conn, streamId.id, 0L, 0, true)
        }
        flushOutgoing()
    }

    companion object {
        private const val MAX_DATAGRAM_SIZE = 1350
    }
}
