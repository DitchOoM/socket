package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.use
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.time.Duration

/** Shared JVM/Android QUIC server engine. */
internal fun commonJvmQuicServerEngine(): QuicServerEngine = JvmQuicServerEngine()

private class JvmQuicServerEngine : QuicServerEngine {
    private val api: QuicheApi = loadQuicheApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer {
        val bufferFactory = BufferFactory.Default

        val config = api.configNew(QUICHE_PROTOCOL_VERSION)

        writeNullTerminatedString(tlsConfig.certChainPath, bufferFactory).use { certBuf ->
            val rc = api.configLoadCertChainFromPemFile(config, certBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            check(rc == 0) { "Failed to load cert chain: $rc" }
        }
        writeNullTerminatedString(tlsConfig.privKeyPath, bufferFactory).use { keyBuf ->
            val rc = api.configLoadPrivKeyFromPemFile(config, keyBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            check(rc == 0) { "Failed to load private key: $rc" }
        }

        encodeAlpnList(quicOptions.alpnProtocols, bufferFactory).use { alpnBuf ->
            api.configSetApplicationProtos(config, alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong(), alpnBuf.remaining())
        }

        applyQuicOptions(quicOptions, CommonJvmQuicConfigCalls(api, config))

        val channel = DatagramChannel.open()
        channel.configureBlocking(false)
        channel.bind(InetSocketAddress(host ?: "0.0.0.0", port))
        val localAddr = channel.localAddress as InetSocketAddress

        return JvmQuicServer(api, config, channel, localAddr, bufferFactory, scope)
    }

    override fun close() {}

    companion object {
        private const val QUICHE_PROTOCOL_VERSION = 0x00000001
    }
}

private fun writeNullTerminatedString(
    str: String,
    factory: BufferFactory,
): com.ditchoom.buffer.PlatformBuffer {
    val buf = factory.allocate(str.length + 1)
    buf.writeString(str, Charset.UTF8)
    buf.writeByte(0)
    buf.resetForRead()
    return buf
}

/**
 * Read a native `size_t` from a buffer's native address.
 * quiche writes size_t directly to native memory in platform byte order.
 * We read it via the underlying direct ByteBuffer to avoid restricted FFM APIs.
 */
private fun readNativeSizeT(buf: com.ditchoom.buffer.PlatformBuffer): Int {
    val bb = (buf.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
    bb.order(java.nio.ByteOrder.nativeOrder())
    return bb.getLong(0).toInt()
}

/**
 * JVM QUIC server. Central receive loop parses DCID to route packets to connections.
 * Each connection is driven by its own [QuicheDriver] — no shared mutexes.
 *
 * Uses NIO [Selector] for async packet receive — zero CPU when idle.
 * Zero-copy packet delivery: each packet is allocated into a fresh buffer
 * and ownership is transferred to the connection's driver.
 */
internal class JvmQuicServer(
    private val api: QuicheApi,
    private val config: QuicheConfig,
    private val channel: DatagramChannel,
    private val localAddr: InetSocketAddress,
    private val bufferFactory: BufferFactory,
    private val scope: CoroutineScope,
) : QuicServer {
    override val port: Int get() = localAddr.port

    private val connectionsByDcid = mutableMapOf<ConnectionIdKey, QuicheDriver>()
    private val acceptedDrivers = Channel<QuicheDriver>(Channel.UNLIMITED)

    @Volatile private var closed = false

    @Volatile private var receiveSelector: Selector? = null
    private val receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }

    override suspend fun connections(handler: suspend QuicScope.() -> Unit) {
        for (driver in acceptedDrivers) {
            scope.launch(Dispatchers.IO) {
                val connJob = SupervisorJob(coroutineContext[Job])
                val connScope = CoroutineScope(coroutineContext + connJob)
                val conn = DriverQuicConnection(driver, connScope)
                try {
                    conn.state.first { it !is QuicConnectionState.Handshaking }
                    if (conn.state.value is QuicConnectionState.Established) {
                        conn.handler()
                    }
                } finally {
                    conn.close()
                    connJob.cancel()
                }
            }
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        receiveSelector?.wakeup() // unblock selector.select() in receiveLoop
        channel.close()
        receiveJob.join()
        for (driver in connectionsByDcid.values.toSet()) {
            driver.destroy()
        }
        connectionsByDcid.clear()
        api.configFree(config)
        acceptedDrivers.close()
    }

    /**
     * Async receive loop using NIO [Selector].
     * Allocates a fresh buffer per packet — zero copy to driver.
     */
    private suspend fun receiveLoop() {
        val selector = Selector.open()
        receiveSelector = selector

        // Header parsing output buffers (reused across iterations — these are scratch space)
        val versionBuf = bufferFactory.allocate(4)
        val typeBuf = bufferFactory.allocate(1)
        val scidBuf = bufferFactory.allocate(QUIC_MAX_CONN_ID_LEN)
        val scidLenBuf = bufferFactory.allocate(8)
        val dcidBuf = bufferFactory.allocate(QUIC_MAX_CONN_ID_LEN)
        val dcidLenBuf = bufferFactory.allocate(8)
        val tokenBuf = bufferFactory.allocate(MAX_TOKEN_LEN)
        val tokenLenBuf = bufferFactory.allocate(8)

        try {
            channel.register(selector, SelectionKey.OP_READ)

            while (!closed) {
                selector.select() // async wait — unblocked by channel.close()
                if (closed) break
                selector.selectedKeys().clear()

                // Drain all available packets after select returns
                while (true) {
                    // Allocate a fresh buffer per packet — ownership transfers to driver (zero-copy)
                    val recvBuf = bufferFactory.allocate(MAX_DATAGRAM_SIZE)
                    val recvByteBuffer = (recvBuf.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
                    recvByteBuffer.clear()

                    val peerAddr: SocketAddress? =
                        try {
                            channel.receive(recvByteBuffer) // non-blocking
                        } catch (_: Exception) {
                            recvBuf.freeNativeMemory()
                            if (closed) return
                            break
                        }

                    if (peerAddr == null) {
                        recvBuf.freeNativeMemory()
                        break // no more packets ready
                    }

                    val received = recvByteBuffer.position()
                    if (received <= 0) {
                        recvBuf.freeNativeMemory()
                        continue
                    }

                    val recvAddr = recvBuf.nativeMemoryAccess!!.nativeAddress.toLong()

                    // Initialize length output buffers with max capacity
                    initSizeTBuffer(scidLenBuf, QUIC_MAX_CONN_ID_LEN)
                    initSizeTBuffer(dcidLenBuf, QUIC_MAX_CONN_ID_LEN)
                    initSizeTBuffer(tokenLenBuf, MAX_TOKEN_LEN)

                    val rc =
                        api.headerInfo(
                            recvAddr,
                            received,
                            QUIC_MAX_CONN_ID_LEN,
                            versionBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                            typeBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                            scidBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                            scidLenBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                            dcidBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                            dcidLenBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                            tokenBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                            tokenLenBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        )
                    if (rc < 0) {
                        recvBuf.freeNativeMemory()
                        continue
                    }

                    val dcidLen = readNativeSizeT(dcidLenBuf)
                    val dcidKey = ConnectionIdKey.from(dcidBuf, dcidLen)
                    val peerInetAddr = peerAddr as InetSocketAddress

                    val existingDriver = connectionsByDcid[dcidKey]
                    if (existingDriver != null) {
                        // Zero-copy: transfer buffer ownership to driver
                        val sendResult = existingDriver.commands.trySend(QuicheCmd.RecvPacket(recvBuf, received))
                        if (sendResult.isFailure) {
                            recvBuf.freeNativeMemory()
                            connectionsByDcid.remove(dcidKey)
                        }
                    } else {
                        // Accept new connection — recvBuf ownership transfers inside
                        val result = acceptNewConnection(recvBuf, received, peerInetAddr)
                        if (result != null) {
                            val (driver, serverScidKey) = result
                            connectionsByDcid[serverScidKey] = driver
                            connectionsByDcid[dcidKey] = driver

                            acceptedDrivers.trySend(driver)
                        }
                    }
                }
            }
        } catch (_: java.nio.channels.ClosedSelectorException) {
            // Shutdown
        } finally {
            receiveSelector = null
            versionBuf.freeNativeMemory()
            typeBuf.freeNativeMemory()
            scidBuf.freeNativeMemory()
            scidLenBuf.freeNativeMemory()
            dcidBuf.freeNativeMemory()
            dcidLenBuf.freeNativeMemory()
            tokenBuf.freeNativeMemory()
            tokenLenBuf.freeNativeMemory()
            try {
                selector.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Write a native size_t value into a buffer for quiche_header_info output params.
     */
    private fun initSizeTBuffer(
        buf: com.ditchoom.buffer.PlatformBuffer,
        value: Int,
    ) {
        val bb = (buf.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
        bb.order(java.nio.ByteOrder.nativeOrder())
        bb.putLong(0, value.toLong())
    }

    /**
     * Accept a new QUIC connection.
     * [recvBuf] ownership is consumed: it is freed after the initial packet is fed to quiche.
     */
    private fun acceptNewConnection(
        recvBuf: com.ditchoom.buffer.PlatformBuffer,
        received: Int,
        peerAddr: InetSocketAddress,
    ): Pair<QuicheDriver, ConnectionIdKey>? {
        val recvAddr = recvBuf.nativeMemoryAccess!!.nativeAddress.toLong()

        val serverScid = generateScid(bufferFactory)
        val serverScidAddr = serverScid.nativeMemoryAccess!!.nativeAddress.toLong()

        val peerSockAddr = peerAddr.toNativeSockAddr(bufferFactory)
        val localSockAddr = localAddr.toNativeSockAddr(bufferFactory)

        val conn =
            try {
                api.accept(
                    serverScidAddr,
                    QUIC_MAX_CONN_ID_LEN,
                    0L,
                    0,
                    localSockAddr.address,
                    localSockAddr.length,
                    peerSockAddr.address,
                    peerSockAddr.length,
                    config,
                )
            } catch (_: Exception) {
                serverScid.freeNativeMemory()
                peerSockAddr.free()
                localSockAddr.free()
                recvBuf.freeNativeMemory()
                return null
            }

        if (conn.handle == 0L) {
            serverScid.freeNativeMemory()
            peerSockAddr.free()
            localSockAddr.free()
            recvBuf.freeNativeMemory()
            return null
        }

        val recvInfo =
            api.recvInfoNew(
                peerSockAddr.address,
                peerSockAddr.length,
                localSockAddr.address,
                localSockAddr.length,
            )
        val sendInfo = api.sendInfoNew()

        // Feed the initial packet before the driver starts — safe, driver not yet running
        api.connRecv(conn, recvAddr, received, recvInfo)
        recvBuf.freeNativeMemory() // initial packet consumed

        val udpChannel = NioUdpChannel(channel, peerAddr)
        val driver =
            QuicheDriver(
                api = api,
                conn = conn,
                bufferFactory = bufferFactory,
                recvInfo = recvInfo,
                sendInfo = sendInfo,
                udpChannel = udpChannel,
                clientMode = false,
                isServer = true,
            )

        driver.start(scope)

        val scidKey = ConnectionIdKey.from(serverScid, QUIC_MAX_CONN_ID_LEN)
        // Don't free sockaddrs — they're owned by the recvInfo/driver now
        // serverScid can be freed now — we have the key
        serverScid.freeNativeMemory()
        return driver to scidKey
    }

    companion object {
        private const val MAX_DATAGRAM_SIZE = 1350
        private const val MAX_TOKEN_LEN = 256
    }
}

/**
 * Key for connection lookup by DCID.
 */
internal class ConnectionIdKey private constructor(
    private val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is ConnectionIdKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun from(
            buffer: com.ditchoom.buffer.PlatformBuffer,
            length: Int,
        ): ConnectionIdKey {
            val bb = (buffer.unwrap() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
            val bytes = ByteArray(length)
            bb.position(0)
            bb.get(bytes, 0, length)
            return ConnectionIdKey(bytes)
        }
    }
}

/**
 * QUIC connection backed by a [QuicheDriver].
 * Used by both client and server — the driver handles the differences.
 */
internal class DriverQuicConnection(
    private val driver: QuicheDriver,
    connectionScope: CoroutineScope,
) : QuicConnection,
    CoroutineScope by connectionScope {
    override val state: StateFlow<QuicConnectionState> = driver.state

    private val bufferFactory = BufferFactory.Default

    override suspend fun openStream(): QuicByteStream {
        try {
            val deferred = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(deferred))
            val slot = deferred.await()
            val adapter = DriverStreamAdapter(driver, slot)
            return QuicByteStream(slot.id, QuicheStreamByteStream(slot.id, adapter, bufferFactory))
        } catch (_: ClosedSendChannelException) {
            throw com.ditchoom.socket.SocketClosedException
                .General("connection closed")
        }
    }

    override suspend fun acceptStream(): QuicByteStream = driver.incomingStreams.receive()

    override fun streams(): Flow<QuicByteStream> = driver.incomingStreams.consumeAsFlow()

    override suspend fun close(error: QuicError) {
        try {
            val deferred = CompletableDeferred<Unit>()
            driver.commands.send(QuicheCmd.Close(error, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            // Already closed
        }
        driver.destroy()
    }
}
