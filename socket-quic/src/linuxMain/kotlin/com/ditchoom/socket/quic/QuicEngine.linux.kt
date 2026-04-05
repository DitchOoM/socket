@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import cnames.structs.quiche_conn
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.IoUringManager
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.linux.io_uring_prep_recv
import com.ditchoom.socket.linux.io_uring_prep_send
import com.ditchoom.socket.linux.socket_getsockname
import com.ditchoom.socket.quic.quiche.QUICHE_ERR_STREAM_RESET
import com.ditchoom.socket.quic.quiche.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.quiche.quiche_config_free
import com.ditchoom.socket.quic.quiche.quiche_config_new
import com.ditchoom.socket.quic.quiche.quiche_config_set_application_protos
import com.ditchoom.socket.quic.quiche.quiche_config_set_disable_active_migration
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_data
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_stream_data_bidi_local
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_stream_data_bidi_remote
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_stream_data_uni
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_streams_bidi
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_streams_uni
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_idle_timeout
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_recv_udp_payload_size
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_send_udp_payload_size
import com.ditchoom.socket.quic.quiche.quiche_config_verify_peer
import com.ditchoom.socket.quic.quiche.quiche_conn_close
import com.ditchoom.socket.quic.quiche.quiche_conn_free
import com.ditchoom.socket.quic.quiche.quiche_conn_is_closed
import com.ditchoom.socket.quic.quiche.quiche_conn_is_established
import com.ditchoom.socket.quic.quiche.quiche_conn_on_timeout
import com.ditchoom.socket.quic.quiche.quiche_conn_recv
import com.ditchoom.socket.quic.quiche.quiche_conn_send
import com.ditchoom.socket.quic.quiche.quiche_conn_stream_recv
import com.ditchoom.socket.quic.quiche.quiche_conn_stream_send
import com.ditchoom.socket.quic.quiche.quiche_conn_timeout_as_nanos
import com.ditchoom.socket.quic.quiche.quiche_connect
import com.ditchoom.socket.quic.quiche.quiche_recv_info
import com.ditchoom.socket.quic.quiche.quiche_send_info
import com.ditchoom.socket.transport.ReadResult
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
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
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

actual fun defaultQuicEngine(): QuicEngine = LinuxQuicEngine()

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

            val config =
                quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())
                    ?: throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create quiche config")

            // ALPN — encode directly into buffer factory buffer (zero-copy, no ByteArray)
            val alpnBuf = encodeAlpnList(quicOptions.alpnProtocols, bufferFactory)
            val alpnPtr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!
            quiche_config_set_application_protos(config, alpnPtr, alpnBuf.remaining().convert())
            alpnBuf.freeNativeMemory()

            quiche_config_set_max_idle_timeout(config, quicOptions.idleTimeout.inWholeMilliseconds.convert())
            quiche_config_set_max_recv_udp_payload_size(config, quicOptions.maxUdpPayloadSize.convert())
            quiche_config_set_max_send_udp_payload_size(config, quicOptions.maxUdpPayloadSize.convert())
            quiche_config_set_initial_max_data(config, quicOptions.initialMaxData.convert())
            quiche_config_set_initial_max_stream_data_bidi_local(
                config,
                quicOptions.initialMaxStreamDataBidiLocal.convert(),
            )
            quiche_config_set_initial_max_stream_data_bidi_remote(
                config,
                quicOptions.initialMaxStreamDataBidiRemote.convert(),
            )
            quiche_config_set_initial_max_stream_data_uni(config, quicOptions.initialMaxStreamDataUni.convert())
            quiche_config_set_initial_max_streams_bidi(config, quicOptions.initialMaxStreamsBidi.convert())
            quiche_config_set_initial_max_streams_uni(config, quicOptions.initialMaxStreamsUni.convert())
            quiche_config_set_disable_active_migration(config, quicOptions.disableActiveMigration)
            quiche_config_verify_peer(config, quicOptions.verifyPeer)
            quiche_config_enable_pacing(config, quicOptions.enablePacing)
            quicOptions.maxPacingRate?.let { quiche_config_set_max_pacing_rate(config, it.convert()) }
            quicOptions.congestionControlAlgorithm?.let {
                quiche_config_set_cc_algorithm(config, quiche_cc_algorithm.byValue(it.value.convert()))
            }
            quicOptions.enableHystart?.let { quiche_config_enable_hystart(config, it) }
            quicOptions.initialCongestionWindowPackets?.let {
                quiche_config_set_initial_congestion_window_packets(config, it.convert())
            }
            quicOptions.maxConnectionWindow?.let { quiche_config_set_max_connection_window(config, it.convert()) }
            quicOptions.maxStreamWindow?.let { quiche_config_set_max_stream_window(config, it.convert()) }
            quicOptions.enablePmtuDiscovery?.let { quiche_config_discover_pmtu(config, it) }
            if (quicOptions.enableEarlyData) quiche_config_enable_early_data(config)
            quicOptions.enableGrease?.let { quiche_config_grease(config, it) }

            // Resolve and create connected UDP socket
            memScoped {
                val fd = socket(AF_INET, SOCK_DGRAM, 0)
                if (fd < 0) throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create UDP socket")

                val hints = alloc<addrinfo>()
                hints.ai_family = AF_INET
                hints.ai_socktype = SOCK_DGRAM
                val resultPtr = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
                if (getaddrinfo(hostname, port.toString(), hints.ptr, resultPtr.ptr) != 0) {
                    close(fd)
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused(hostname, port, platformError = "DNS resolution failed")
                }
                val addrInfo = resultPtr.value!!.pointed
                val peerSockAddr: CPointer<sockaddr> = addrInfo.ai_addr!!
                val peerSockAddrLen = addrInfo.ai_addrlen

                if (connect(fd, peerSockAddr, peerSockAddrLen) < 0) {
                    freeaddrinfo(resultPtr.value)
                    close(fd)
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused(hostname, port, platformError = "UDP connect failed")
                }

                // Get local address after connect
                val localAddr = alloc<sockaddr_in>()
                val localAddrLen = alloc<kotlinx.cinterop.UIntVar>()
                localAddrLen.value = sizeOf<sockaddr_in>().convert()
                socket_getsockname(
                    fd,
                    localAddr.ptr.reinterpret(),
                    localAddrLen.ptr,
                )

                // SCID — generate random bytes directly into buffer (no ByteArray)
                val scidBuf = bufferFactory.allocate(MAX_CONN_ID_LEN)
                for (i in 0 until MAX_CONN_ID_LEN) {
                    scidBuf.writeByte(Random.nextInt(256).toByte())
                }
                scidBuf.resetForRead()
                val scidPtr = scidBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!

                val conn =
                    quiche_connect(
                        hostname,
                        scidPtr,
                        MAX_CONN_ID_LEN.convert(),
                        localAddr.ptr.reinterpret(),
                        sizeOf<sockaddr_in>().convert(),
                        peerSockAddr,
                        peerSockAddrLen,
                        config,
                    ) ?: run {
                        scidBuf.freeNativeMemory()
                        freeaddrinfo(resultPtr.value)
                        close(fd)
                        quiche_config_free(config)
                        throw SocketConnectionException.Refused(hostname, port, platformError = "quiche_connect failed")
                    }

                scidBuf.freeNativeMemory()

                // Keep peer sockaddr alive for recvInfo — copy into buffer factory
                val peerAddrBuf = bufferFactory.allocate(peerSockAddrLen.toInt())
                val peerAddrDst = peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
                val peerAddrSrc = peerSockAddr.reinterpret<ByteVar>()
                platform.posix.memcpy(peerAddrDst, peerAddrSrc, peerSockAddrLen.convert())
                peerAddrBuf.resetForRead()

                val localAddrBuf = bufferFactory.allocate(sizeOf<sockaddr_in>().toInt())
                val localAddrDst = localAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
                platform.posix.memcpy(localAddrDst, localAddr.ptr, sizeOf<sockaddr_in>().convert())
                localAddrBuf.resetForRead()

                freeaddrinfo(resultPtr.value)
                quiche_config_free(config)

                val quicConn =
                    LinuxQuicConnection(
                        conn,
                        fd,
                        bufferFactory,
                        peerAddrBuf,
                        peerSockAddrLen.toInt(),
                        localAddrBuf,
                        sizeOf<sockaddr_in>().toInt(),
                        scope,
                    )
                quicConn.start()
                quicConn.awaitEstablished(timeout)
                quicConn
            }
        }

    override fun close() {}

    companion object {
        private const val MAX_CONN_ID_LEN = 20
    }
}

private class LinuxQuicConnection(
    private val conn: CPointer<quiche_conn>,
    private val udpFd: Int,
    private val bufferFactory: BufferFactory,
    private val peerAddrBuf: PlatformBuffer,
    private val peerAddrLen: Int,
    private val localAddrBuf: PlatformBuffer,
    private val localAddrLen: Int,
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
        val recvPtr = udpBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        val sendPtr = sendBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        val peerAddr = peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<sockaddr>()!!
        val localAddr = localAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<sockaddr>()!!

        while (!closed) {
            // 1. Receive UDP via io_uring
            val received =
                try {
                    IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
                        io_uring_prep_recv(sqe, udpFd, recvPtr, MAX_DATAGRAM_SIZE.convert(), 0)
                    }
                } catch (_: Exception) {
                    if (closed) break
                    -1
                }

            if (received > 0) {
                connMutex.withLock {
                    memScoped {
                        val recvInfo = alloc<quiche_recv_info>()
                        recvInfo.from = peerAddr
                        recvInfo.from_len = peerAddrLen.convert()
                        recvInfo.to = localAddr
                        recvInfo.to_len = localAddrLen.convert()
                        quiche_conn_recv(conn, recvPtr.reinterpret(), received.convert(), recvInfo.ptr)
                    }
                }
            }

            // 2. Update state
            connMutex.withLock {
                if (quiche_conn_is_established(conn) && _state.value is QuicConnectionState.Handshaking) {
                    _state.value = QuicConnectionState.Established("h3")
                }
                if (quiche_conn_is_closed(conn)) {
                    _state.value = QuicConnectionState.Closed(null)
                    break
                }
            }

            // 3. Flush outgoing via io_uring
            flushOutgoing(sendPtr)

            // 4. Handle quiche timeout
            connMutex.withLock {
                if (quiche_conn_timeout_as_nanos(conn) == 0UL) {
                    quiche_conn_on_timeout(conn)
                }
            }
        }
    }

    private suspend fun flushOutgoing(sendPtr: CPointer<ByteVar>) {
        connMutex.withLock {
            memScoped {
                val sendInfo = alloc<quiche_send_info>()
                while (true) {
                    val written =
                        quiche_conn_send(conn, sendPtr.reinterpret(), MAX_DATAGRAM_SIZE.convert(), sendInfo.ptr)
                    if (written <= 0) break
                    IoUringManager.submitAndWait(1.seconds) { sqe, _ ->
                        io_uring_prep_send(sqe, udpFd, sendPtr, written.convert(), 0)
                    }
                }
            }
        }
    }

    override suspend fun openStream(): QuicByteStream {
        check(!closed) { "LinuxQuicConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        val streamId = QuicStreamId(nextClientStreamId)
        nextClientStreamId += 4
        return QuicByteStream(streamId, QuicheStreamByteStream(streamId, this, bufferFactory))
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
            quiche_conn_close(
                conn,
                error is QuicError.ApplicationError,
                error.code.convert(),
                null,
                0.convert(),
            )
        }
        val sendPtr = sendBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        flushOutgoing(sendPtr)

        eventLoopJob?.cancel()
        close(udpFd)
        quiche_conn_free(conn)
        udpBuf.freeNativeMemory()
        sendBuf.freeNativeMemory()
        peerAddrBuf.freeNativeMemory()
        localAddrBuf.freeNativeMemory()
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
                        val fin = alloc<BooleanVar>()
                        val errorCode = alloc<ULongVar>()
                        quiche_conn_stream_recv(
                            conn,
                            streamId.id.convert(),
                            ptr,
                            bufferSize.convert(),
                            fin.ptr,
                            errorCode.ptr,
                        )
                    }
                }
            }

        if (result < 0) {
            buffer.freeNativeMemory()
            return when (result.toInt()) {
                QUICHE_ERR_STREAM_RESET -> ReadResult.Reset
                else -> ReadResult.End
            }
        }

        if (result.toInt() == 0) {
            buffer.freeNativeMemory()
            return ReadResult.End
        }

        buffer.position(result.toInt())
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
        val addr = nma.nativeAddress + buffer.position().toLong()
        val ptr = addr.toCPointer<UByteVar>()!!

        val written =
            withTimeout(timeout) {
                connMutex.withLock {
                    memScoped {
                        val errorCode = alloc<ULongVar>()
                        quiche_conn_stream_send(
                            conn,
                            streamId.id.convert(),
                            ptr,
                            buffer.remaining().convert(),
                            false,
                            errorCode.ptr,
                        )
                    }
                }
            }

        if (written < 0) throw SocketClosedException.General("quiche stream write error: $written")

        val sendPtr = sendBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        flushOutgoing(sendPtr)
        return written.toInt()
    }

    override suspend fun streamClose(streamId: QuicStreamId) {
        connMutex.withLock {
            memScoped {
                val errorCode = alloc<ULongVar>()
                quiche_conn_stream_send(conn, streamId.id.convert(), null, 0.convert(), true, errorCode.ptr)
            }
        }
        val sendPtr = sendBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        flushOutgoing(sendPtr)
    }

    companion object {
        private const val MAX_DATAGRAM_SIZE = 1350
    }
}
