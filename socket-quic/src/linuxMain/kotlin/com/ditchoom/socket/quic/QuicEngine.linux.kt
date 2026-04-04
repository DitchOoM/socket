package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
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
import com.ditchoom.socket.quic.native.quiche_conn_close
import com.ditchoom.socket.quic.native.quiche_conn_free
import com.ditchoom.socket.quic.native.quiche_conn_stream_recv
import com.ditchoom.socket.quic.native.quiche_conn_stream_send
import com.ditchoom.socket.quic.native.quiche_connect
import com.ditchoom.socket.transport.ReadResult
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = LinuxQuicEngine()

/**
 * Linux QUIC engine using quiche via cinterop + io_uring for UDP I/O.
 *
 * Zero-copy: buffer native addresses from [BufferFactory.deterministic]
 * are passed directly to quiche C functions via cinterop pointers.
 * No intermediate copies at the K/N boundary.
 */
@OptIn(ExperimentalForeignApi::class)
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

            // Set ALPN protocols — allocate via bufferFactory, pass native address
            val alpnBytes = QuicheApi.encodeAlpnProtos(quicOptions.alpnProtocols)
            val alpnBuf = bufferFactory.allocate(alpnBytes.size)
            alpnBuf.writeBytes(alpnBytes)
            alpnBuf.resetForRead()
            val alpnPtr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
            quiche_config_set_application_protos(config, alpnPtr.reinterpret(), alpnBytes.size.convert())
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

            // 2. Generate source connection ID
            val scid = ByteArray(MAX_CONN_ID_LEN)
            Random.nextBytes(scid)
            val scidBuf = bufferFactory.allocate(scid.size)
            scidBuf.writeBytes(scid)
            scidBuf.resetForRead()
            val scidPtr = scidBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!

            // 3. Create quiche connection
            // TODO: create proper sockaddr structs for local/peer addresses
            // TODO: open UDP socket via io_uring
            val conn =
                quiche_connect(
                    hostname,
                    scidPtr.reinterpret(),
                    scid.size.convert(),
                    null, // local sockaddr TODO
                    0.convert(),
                    null, // peer sockaddr TODO
                    0.convert(),
                    config,
                ) ?: throw SocketConnectionException.Refused("quiche_connect failed for $hostname:$port")

            scidBuf.freeNativeMemory()
            quiche_config_free(config)

            // 4. Create connection wrapper
            LinuxQuicConnection(conn, bufferFactory, scope)
        }

    override fun close() {}

    companion object {
        private const val MAX_CONN_ID_LEN = 20
    }
}

@OptIn(ExperimentalForeignApi::class)
private class LinuxQuicConnection(
    private val conn: CPointer<cnames.structs.quiche_conn>,
    private val bufferFactory: BufferFactory,
    private val scope: CoroutineScope,
) : QuicConnection,
    QuicheStreamAdapter {
    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)
    override val state: StateFlow<QuicConnectionState> = _state

    private val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    private var nextClientStreamId = 0L
    private var closed = false

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
        quiche_conn_close(conn, error is QuicError.ApplicationError, error.code.convert(), null, 0.convert())
        quiche_conn_free(conn)
        incomingStreams.close()
        _state.value = QuicConnectionState.Closed(error)
    }

    // --- QuicheStreamAdapter (zero-copy via native pointers) ---

    override suspend fun streamRead(
        streamId: QuicStreamId,
        bufferFactory: BufferFactory,
        bufferSize: Int,
        timeout: Duration,
    ): ReadResult {
        val buffer = bufferFactory.allocate(bufferSize) as PlatformBuffer
        val addr = buffer.nativeMemoryAccess!!.nativeAddress
        val ptr = addr.toCPointer<ByteVar>()!!

        memScoped {
            val fin = alloc<kotlinx.cinterop.BooleanVar>()
            val result =
                quiche_conn_stream_recv(
                    conn,
                    streamId.id.convert(),
                    ptr.reinterpret(),
                    bufferSize.convert(),
                    fin.ptr,
                )

            if (result < 0) {
                buffer.freeNativeMemory()
                return ReadResult.End
            }

            if (result.toInt() == 0 && fin.value) {
                buffer.freeNativeMemory()
                return ReadResult.End
            }

            buffer.position(result.toInt())
            buffer.resetForRead()
            return ReadResult.Data(buffer)
        }
    }

    override suspend fun streamWrite(
        streamId: QuicStreamId,
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val nativeAccess =
            (buffer as PlatformBuffer).nativeMemoryAccess
                ?: throw IllegalArgumentException("Buffer must have native memory access for zero-copy write")
        val addr = nativeAccess.nativeAddress + buffer.position().toULong()
        val ptr = addr.toCPointer<ByteVar>()!!
        val remaining = buffer.remaining()

        val written =
            quiche_conn_stream_send(
                conn,
                streamId.id.convert(),
                ptr.reinterpret(),
                remaining.convert(),
                false,
            )

        if (written < 0) {
            throw SocketClosedException.General("quiche stream write error: $written")
        }

        return written.toInt()
    }

    override suspend fun streamClose(streamId: QuicStreamId) {
        quiche_conn_stream_send(conn, streamId.id.convert(), null, 0.convert(), true)
    }
}

private val PlatformBuffer.nativeMemoryAccess
    get() = this as? com.ditchoom.buffer.NativeMemoryAccess
