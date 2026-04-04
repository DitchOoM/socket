@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.quic.native.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.native.QUICHE_ERR_STREAM_RESET
import com.ditchoom.socket.quic.native.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.native.quiche_config_free
import com.ditchoom.socket.quic.native.quiche_config_new
import com.ditchoom.socket.quic.native.quiche_config_set_application_protos
import com.ditchoom.socket.quic.native.quiche_config_set_disable_active_migration
import com.ditchoom.socket.quic.native.quiche_config_set_initial_max_data
import com.ditchoom.socket.quic.native.quiche_config_set_initial_max_stream_data_bidi_local
import com.ditchoom.socket.quic.native.quiche_config_set_initial_max_stream_data_bidi_remote
import com.ditchoom.socket.quic.native.quiche_config_set_initial_max_stream_data_uni
import com.ditchoom.socket.quic.native.quiche_config_set_initial_max_streams_bidi
import com.ditchoom.socket.quic.native.quiche_config_set_initial_max_streams_uni
import com.ditchoom.socket.quic.native.quiche_config_set_max_idle_timeout
import com.ditchoom.socket.quic.native.quiche_config_set_max_recv_udp_payload_size
import com.ditchoom.socket.quic.native.quiche_config_set_max_send_udp_payload_size
import com.ditchoom.socket.quic.native.quiche_config_verify_peer
import com.ditchoom.socket.quic.native.quiche_conn
import com.ditchoom.socket.quic.native.quiche_conn_close
import com.ditchoom.socket.quic.native.quiche_conn_free
import com.ditchoom.socket.quic.native.quiche_conn_is_closed
import com.ditchoom.socket.quic.native.quiche_conn_is_established
import com.ditchoom.socket.quic.native.quiche_conn_on_timeout
import com.ditchoom.socket.quic.native.quiche_conn_recv
import com.ditchoom.socket.quic.native.quiche_conn_send
import com.ditchoom.socket.quic.native.quiche_conn_stream_recv
import com.ditchoom.socket.quic.native.quiche_conn_stream_send
import com.ditchoom.socket.quic.native.quiche_conn_timeout_as_nanos
import com.ditchoom.socket.quic.native.quiche_connect
import com.ditchoom.socket.quic.native.quiche_recv_info
import com.ditchoom.socket.quic.native.quiche_send_info
import com.ditchoom.socket.transport.ReadResult
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.connect
import platform.posix.getaddrinfo
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

actual fun defaultQuicEngine(): QuicEngine = LinuxQuicEngine()

/**
 * Linux QUIC engine using quiche via cinterop + POSIX UDP sockets.
 *
 * Zero-copy: buffer native addresses from [BufferFactory.deterministic]
 * are passed directly to quiche C functions via cinterop pointers.
 */
private class LinuxQuicEngine : QuicEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
    ): QuicConnection =
        withTimeout(timeout) {
            val bufferFactory = connectionOptions.bufferFactory

            // 1. Create quiche config
            val config =
                quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())
                    ?: throw SocketConnectionException.Refused("Failed to create quiche config")

            // Set ALPN — write into a buffer from the factory, pass native address
            val alpnBytes = QuicheApi.encodeAlpnProtos(quicOptions.alpnProtocols)
            val alpnBuf = bufferFactory.allocate(alpnBytes.size)
            alpnBuf.writeBytes(alpnBytes)
            alpnBuf.resetForRead()
            val alpnPtr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!
            quiche_config_set_application_protos(config, alpnPtr, alpnBytes.size.convert())
            alpnBuf.freeNativeMemory()

            quiche_config_set_max_idle_timeout(config, quicOptions.idleTimeout.inWholeMilliseconds.convert())
            quiche_config_set_max_recv_udp_payload_size(config, quicOptions.maxUdpPayloadSize.convert())
            quiche_config_set_max_send_udp_payload_size(config, quicOptions.maxUdpPayloadSize.convert())
            quiche_config_set_initial_max_data(config, quicOptions.initialMaxData.convert())
            quiche_config_set_initial_max_stream_data_bidi_local(config, quicOptions.initialMaxStreamDataBidiLocal.convert())
            quiche_config_set_initial_max_stream_data_bidi_remote(config, quicOptions.initialMaxStreamDataBidiRemote.convert())
            quiche_config_set_initial_max_stream_data_uni(config, quicOptions.initialMaxStreamDataUni.convert())
            quiche_config_set_initial_max_streams_bidi(config, quicOptions.initialMaxStreamsBidi.convert())
            quiche_config_set_initial_max_streams_uni(config, quicOptions.initialMaxStreamsUni.convert())
            quiche_config_set_disable_active_migration(config, quicOptions.disableActiveMigration)
            quiche_config_verify_peer(config, quicOptions.verifyPeer)

            // 2. Resolve address and create UDP socket
            memScoped {
                val localAddr = alloc<sockaddr_in>()
                val peerAddr = alloc<sockaddr_in>()

                // Create UDP socket
                val fd = socket(AF_INET, SOCK_DGRAM, 0)
                if (fd < 0) throw SocketConnectionException.Refused("Failed to create UDP socket")

                // Resolve and connect to peer
                val hints = alloc<platform.posix.addrinfo>()
                hints.ai_family = AF_INET
                hints.ai_socktype = SOCK_DGRAM
                val resultPtr = alloc<kotlinx.cinterop.CPointerVar<platform.posix.addrinfo>>()
                val resolveResult = getaddrinfo(hostname, port.toString(), hints.ptr, resultPtr.ptr)
                if (resolveResult != 0) {
                    close(fd)
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused("DNS resolution failed for $hostname")
                }
                val addrInfo = resultPtr.value!!.pointed
                if (connect(fd, addrInfo.ai_addr, addrInfo.ai_addrlen) < 0) {
                    close(fd)
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused("UDP connect failed for $hostname:$port")
                }
                platform.posix.freeaddrinfo(resultPtr.value)

                // 3. Generate SCID via buffer factory
                val scid = ByteArray(MAX_CONN_ID_LEN)
                Random.nextBytes(scid)
                val scidBuf = bufferFactory.allocate(scid.size)
                scidBuf.writeBytes(scid)
                scidBuf.resetForRead()
                val scidPtr = scidBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!

                // 4. Create quiche connection
                val conn =
                    quiche_connect(
                        hostname,
                        scidPtr,
                        scid.size.convert(),
                        addrInfo.ai_addr,
                        addrInfo.ai_addrlen,
                        addrInfo.ai_addr,
                        addrInfo.ai_addrlen, // local = peer for now (connected UDP)
                        config,
                    ) ?: run {
                        scidBuf.freeNativeMemory()
                        close(fd)
                        quiche_config_free(config)
                        throw SocketConnectionException.Refused("quiche_connect failed for $hostname:$port")
                    }

                scidBuf.freeNativeMemory()
                quiche_config_free(config)

                // 5. Create connection and start event loop
                val quicConn = LinuxQuicConnection(conn, fd, bufferFactory, scope)
                quicConn.start()

                // 6. Wait for handshake
                quicConn.awaitEstablished(timeout)
                quicConn
            }
        }

    override fun close() {}

    companion object {
        private const val MAX_CONN_ID_LEN = 20
    }
}

/**
 * Linux QUIC connection backed by quiche cinterop + POSIX UDP.
 *
 * Zero-copy data path:
 *   recv(fd, nativePtr, ...) → quiche_conn_recv(conn, nativePtr, ...) → decrypts in-place
 *   quiche_conn_stream_recv(conn, streamId, nativePtr, ...) → app buffer (one copy for reassembly)
 */
private class LinuxQuicConnection(
    private val conn: CPointer<quiche_conn>,
    private val udpFd: Int,
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
    private var eventLoopJob: Job? = null

    private val udpBuf: PlatformBuffer = bufferFactory.allocate(MAX_DATAGRAM_SIZE)
    private val sendBuf: PlatformBuffer = bufferFactory.allocate(MAX_DATAGRAM_SIZE)

    fun start() {
        eventLoopJob = scope.launch { eventLoop() }
    }

    suspend fun awaitEstablished(timeout: Duration) {
        withTimeout(timeout) {
            while (_state.value is QuicConnectionState.Handshaking) {
                delay(1.milliseconds)
            }
        }
    }

    private suspend fun eventLoop() {
        val recvPtr = udpBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<kotlinx.cinterop.ByteVar>()!!
        val sendPtr = sendBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<kotlinx.cinterop.ByteVar>()!!

        while (!closed) {
            // 1. Receive UDP packet
            val received = recv(udpFd, recvPtr, MAX_DATAGRAM_SIZE.convert(), 0)

            if (received > 0) {
                connMutex.withLock {
                    memScoped {
                        val recvInfo = alloc<quiche_recv_info>()
                        // For connected UDP socket, from/to can be minimal
                        val dummyAddr = alloc<sockaddr_in>()
                        recvInfo.from = dummyAddr.ptr.reinterpret()
                        recvInfo.from_len = sizeOf<sockaddr_in>().convert()
                        recvInfo.to = dummyAddr.ptr.reinterpret()
                        recvInfo.to_len = sizeOf<sockaddr_in>().convert()

                        quiche_conn_recv(
                            conn,
                            recvPtr.reinterpret(),
                            received.convert(),
                            recvInfo.ptr,
                        )
                    }
                }
            }

            // 2. Update state
            connMutex.withLock {
                if (quiche_conn_is_established(conn) && _state.value is QuicConnectionState.Handshaking) {
                    _state.value = QuicConnectionState.Established("h3") // TODO: read negotiated ALPN
                }
                if (quiche_conn_is_closed(conn)) {
                    _state.value = QuicConnectionState.Closed(null)
                    break
                }
            }

            // 3. Flush outgoing packets
            flushOutgoing(sendPtr)

            // 4. Handle timeout
            connMutex.withLock {
                val timeoutNanos = quiche_conn_timeout_as_nanos(conn)
                if (timeoutNanos == 0UL) {
                    quiche_conn_on_timeout(conn)
                }
            }

            if (received <= 0) delay(1.milliseconds)
        }
    }

    private suspend fun flushOutgoing(sendPtr: CPointer<kotlinx.cinterop.ByteVar>) {
        connMutex.withLock {
            memScoped {
                val sendInfo = alloc<quiche_send_info>()
                while (true) {
                    val written =
                        quiche_conn_send(
                            conn,
                            sendPtr.reinterpret(),
                            MAX_DATAGRAM_SIZE.convert(),
                            sendInfo.ptr,
                        )
                    if (written <= 0) break
                    send(udpFd, sendPtr, written.convert(), 0)
                }
            }
        }
    }

    // --- QuicConnection interface ---

    override suspend fun openStream(): QuicByteStream {
        check(!closed) { "LinuxQuicConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        val streamId = QuicStreamId(nextClientStreamId)
        nextClientStreamId += 4
        val byteStream = QuicheStreamByteStream(streamId, this, bufferFactory)
        return QuicByteStream(streamId, byteStream)
    }

    override suspend fun acceptStream(): QuicByteStream {
        check(!closed) { "LinuxQuicConnection is closed" }
        return incomingStreams.receive()
    }

    override fun streams(): Flow<QuicByteStream> = incomingStreams.consumeAsFlow()

    override suspend fun close(error: QuicError) {
        if (closed) return
        closed = true
        _state.value = QuicConnectionState.Draining

        connMutex.withLock {
            quiche_conn_close(conn, error is QuicError.ApplicationError, error.code.convert(), null, 0.convert())
        }
        val sendPtr = sendBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<kotlinx.cinterop.ByteVar>()!!
        flushOutgoing(sendPtr)

        eventLoopJob?.cancel()
        close(udpFd)
        quiche_conn_free(conn)
        udpBuf.freeNativeMemory()
        sendBuf.freeNativeMemory()
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
        val buffer = bufferFactory.allocate(bufferSize) as PlatformBuffer
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!

        val result =
            withTimeout(timeout) {
                connMutex.withLock {
                    memScoped {
                        val fin = alloc<kotlinx.cinterop.BooleanVar>()
                        val errorCode = alloc<kotlinx.cinterop.ULongVar>()
                        val bytesRead =
                            quiche_conn_stream_recv(
                                conn,
                                streamId.id.convert(),
                                ptr,
                                bufferSize.convert(),
                                fin.ptr,
                                errorCode.ptr,
                            )
                        Triple(bytesRead, fin.value, errorCode.value)
                    }
                }
            }

        val (bytesRead, fin, _) = result
        if (bytesRead < 0) {
            buffer.freeNativeMemory()
            return when (bytesRead.toInt()) {
                QUICHE_ERR_DONE -> if (fin) ReadResult.End else ReadResult.End
                QUICHE_ERR_STREAM_RESET -> ReadResult.Reset
                else -> ReadResult.End
            }
        }

        if (bytesRead.toInt() == 0 && fin) {
            buffer.freeNativeMemory()
            return ReadResult.End
        }

        buffer.position(bytesRead.toInt())
        buffer.resetForRead()
        return ReadResult.Data(buffer)
    }

    override suspend fun streamWrite(
        streamId: QuicStreamId,
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val nma =
            buffer.nativeMemoryAccess
                ?: throw IllegalArgumentException("Buffer must have native memory for zero-copy write")
        val addr = nma.nativeAddress + buffer.position().toULong()
        val ptr = addr.toCPointer<UByteVar>()!!
        val remaining = buffer.remaining()

        val written =
            withTimeout(timeout) {
                connMutex.withLock {
                    memScoped {
                        val errorCode = alloc<kotlinx.cinterop.ULongVar>()
                        quiche_conn_stream_send(
                            conn,
                            streamId.id.convert(),
                            ptr,
                            remaining.convert(),
                            false,
                            errorCode.ptr,
                        )
                    }
                }
            }

        if (written < 0) {
            throw SocketClosedException.General("quiche stream write error: $written")
        }

        // Flush after write
        val sendPtr = sendBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<kotlinx.cinterop.ByteVar>()!!
        flushOutgoing(sendPtr)

        return written.toInt()
    }

    override suspend fun streamClose(streamId: QuicStreamId) {
        connMutex.withLock {
            memScoped {
                val errorCode = alloc<kotlinx.cinterop.ULongVar>()
                quiche_conn_stream_send(conn, streamId.id.convert(), null, 0.convert(), true, errorCode.ptr)
            }
        }
        val sendPtr = sendBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<kotlinx.cinterop.ByteVar>()!!
        flushOutgoing(sendPtr)
    }

    companion object {
        private const val MAX_DATAGRAM_SIZE = 1350
    }
}
