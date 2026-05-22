@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.bufferHashCode
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.linux.socket_getsockname
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.INADDR_ANY
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.htonl
import platform.posix.htons
import platform.posix.memcpy
import platform.posix.ntohs
import platform.posix.sockaddr_in
import platform.posix.sockaddr_storage
import platform.posix.socket
import kotlin.time.Duration

actual fun defaultQuicServerEngine(): QuicServerEngine = LinuxQuicServerEngine()

private class LinuxQuicServerEngine : QuicServerEngine {
    private val api: QuicheApi = CinteropQuicheApi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer {
        val bufferFactory = BufferFactory.deterministic()

        val config = api.configNew(QUICHE_PROTOCOL_VERSION)

        // Load TLS cert chain
        writeNullTerminatedString(tlsConfig.certChainPath, bufferFactory).let { certBuf ->
            val rc = api.configLoadCertChainFromPemFile(config, certBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            certBuf.freeNativeMemory()
            check(rc == 0) { "Failed to load cert chain: $rc" }
        }

        // Load TLS private key
        writeNullTerminatedString(tlsConfig.privKeyPath, bufferFactory).let { keyBuf ->
            val rc = api.configLoadPrivKeyFromPemFile(config, keyBuf.nativeMemoryAccess!!.nativeAddress.toLong())
            keyBuf.freeNativeMemory()
            check(rc == 0) { "Failed to load private key: $rc" }
        }

        // ALPN
        val alpnBuf = encodeAlpnList(quicOptions.alpnProtocols, bufferFactory)
        api.configSetApplicationProtos(config, alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong(), alpnBuf.remaining())
        alpnBuf.freeNativeMemory()

        applyQuicOptions(quicOptions, LinuxQuicConfigCalls(config.handle.toCPointer()!!))

        // Create & bind UDP socket
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "Failed to create UDP socket" }

        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_addr.s_addr = htonl(INADDR_ANY)
            addr.sin_port = htons(port.toUShort())
            val bindRc = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(bindRc == 0) { "Failed to bind UDP socket to port $port" }
        }

        // Get assigned port. memScoped.alloc does not zero-init; we must check the
        // getsockname return value — a silent failure leaves sin_family as garbage, which
        // SIGABRTs through Rust's std_addr_from_c panic when quiche_accept reads it.
        val boundPort =
            memScoped {
                val boundAddr = alloc<sockaddr_in>()
                platform.posix.memset(boundAddr.ptr, 0, sizeOf<sockaddr_in>().convert())
                val boundLen = alloc<UIntVar>()
                boundLen.value = sizeOf<sockaddr_in>().convert()
                val rc = socket_getsockname(fd, boundAddr.ptr.reinterpret(), boundLen.ptr)
                check(rc == 0) { "socket_getsockname(boundPort) returned $rc" }
                check(boundAddr.sin_family.toInt() == AF_INET) {
                    "socket_getsockname(boundPort) sin_family=${boundAddr.sin_family.toInt()} (expected AF_INET=$AF_INET)"
                }
                ntohs(boundAddr.sin_port).toInt()
            }

        // Copy local address to heap buffer for recvInfo (outlives memScoped). Same
        // init/check discipline as above — this buffer is handed to quiche via api.accept
        // and api.recvInfoNew, so a garbage sin_family here poisons every accepted connection.
        val localAddrBuf = bufferFactory.allocate(sizeOf<sockaddr_in>().toInt())
        memScoped {
            val localAddr = alloc<sockaddr_in>()
            platform.posix.memset(localAddr.ptr, 0, sizeOf<sockaddr_in>().convert())
            val localLen = alloc<UIntVar>()
            localLen.value = sizeOf<sockaddr_in>().convert()
            val rc = socket_getsockname(fd, localAddr.ptr.reinterpret(), localLen.ptr)
            check(rc == 0) { "socket_getsockname(localAddr) returned $rc" }
            check(localAddr.sin_family.toInt() == AF_INET) {
                "socket_getsockname(localAddr) sin_family=${localAddr.sin_family.toInt()} (expected AF_INET=$AF_INET)"
            }
            val dst = localAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
            memcpy(dst, localAddr.ptr, sizeOf<sockaddr_in>().convert())
        }

        return LinuxQuicServer(
            api = api,
            config = config,
            serverChannel = IoUringUdpServerChannel(fd),
            boundPort = boundPort,
            localAddrBuf = localAddrBuf,
            bufferFactory = bufferFactory,
            scope = scope,
        )
    }

    override fun close() {}

    companion object {
        private const val QUICHE_PROTOCOL_VERSION = 0x00000001
    }
}

/**
 * Linux QUIC server. Central receive loop uses [IoUringUdpServerChannel.recvFrom]
 * to parse QUIC headers and route packets by DCID to the appropriate [QuicheDriver].
 *
 * Mirrors [JvmQuicServer] architecture — each connection driven by its own [QuicheDriver],
 * no shared mutexes, zero-copy packet delivery.
 */
private class LinuxQuicServer(
    private val api: QuicheApi,
    private val config: QuicheConfig,
    private val serverChannel: IoUringUdpServerChannel,
    private val boundPort: Int,
    private val localAddrBuf: PlatformBuffer,
    private val bufferFactory: BufferFactory,
    private val scope: CoroutineScope,
) : QuicServer {
    override val port: Int get() = boundPort

    private val connectionsByDcid = mutableMapOf<ConnectionIdKey, QuicheDriver>()
    private val acceptedDrivers = Channel<QuicheDriver>(Channel.UNLIMITED)

    /**
     * Queue for drivers that need their [connectionsByDcid] entries removed.
     * Connection handlers add drivers here after close; the receive loop drains it.
     * This keeps all map mutations on the receive loop coroutine.
     */
    private val driverCleanupCh = Channel<QuicheDriver>(Channel.UNLIMITED)

    @kotlin.concurrent.Volatile
    private var closed = false

    private val receiveJob = scope.launch(Dispatchers.Default) { receiveLoop() }

    override suspend fun connections(handler: suspend QuicScope.() -> Unit) {
        for (driver in acceptedDrivers) {
            scope.launch(Dispatchers.Default) {
                val connJob = SupervisorJob(coroutineContext[Job])
                val connScope = CoroutineScope(coroutineContext + connJob)
                val conn = LinuxServerQuicConnection(driver, bufferFactory, connScope)
                try {
                    conn.state.first { it !is QuicConnectionState.Handshaking }
                    if (conn.state.value is QuicConnectionState.Established) {
                        conn.handler()
                    }
                } finally {
                    conn.close()
                    driverCleanupCh.trySend(driver)
                    connJob.cancel()
                }
            }
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        // Order is load-bearing.
        // 1. Stop receiving FIRST: closing the fd makes any io_uring recvmsg in-flight
        //    return -ECANCELED, which the loop's catch{} + closed-check translates into a
        //    clean exit. cancelAndJoin then waits for the loop to fully exit.
        // 2. Only after the receive loop is gone is it safe to destroy drivers — otherwise
        //    the loop may be mid-routing a packet to existingDriver.commands.trySend(...)
        //    and touch a freed driver (SIGSEGV ~17% in linuxX64Test serverAcceptsConnection).
        // 3. Only after the loop is gone is it safe to free the recv buffers it shares
        //    with the kernel via io_uring SQEs ("malloc(): unsorted double linked list
        //    corrupted" otherwise).
        serverChannel.closeFd()
        receiveJob.cancelAndJoin()
        for (driver in connectionsByDcid.values.toSet()) {
            driver.destroy()
        }
        connectionsByDcid.clear()
        api.configFree(config)
        acceptedDrivers.close()
        serverChannel.freeBuffers()
        localAddrBuf.freeNativeMemory()
    }

    /**
     * Central receive loop. Suspends on io_uring until a UDP packet arrives,
     * parses the QUIC header to extract the DCID, and routes to the owning
     * [QuicheDriver] or accepts a new connection.
     */
    private suspend fun receiveLoop() {
        // Header parsing scratch buffers (reused across iterations)
        val versionBuf = bufferFactory.allocate(4)
        val typeBuf = bufferFactory.allocate(1)
        val scidBuf = bufferFactory.allocate(QUIC_MAX_CONN_ID_LEN)
        val scidLenBuf = bufferFactory.allocate(8)
        val dcidBuf = bufferFactory.allocate(QUIC_MAX_CONN_ID_LEN)
        val dcidLenBuf = bufferFactory.allocate(8)
        val tokenBuf = bufferFactory.allocate(MAX_TOKEN_LEN)
        val tokenLenBuf = bufferFactory.allocate(8)

        try {
            while (!closed) {
                // Remove entries for drivers closed by connection handlers
                drainCleanupChannel()

                // Allocate a fresh buffer per packet — ownership transfers to driver (zero-copy)
                val recvBuf = bufferFactory.allocate(MAX_DATAGRAM_SIZE)

                val recvResult =
                    try {
                        serverChannel.recvFrom(recvBuf)
                    } catch (_: Exception) {
                        recvBuf.freeNativeMemory()
                        if (closed) return
                        continue
                    }

                if (recvResult.bytesReceived <= 0) {
                    recvBuf.freeNativeMemory()
                    continue
                }

                val recvAddr = recvBuf.nativeMemoryAccess!!.nativeAddress.toLong()

                // Initialize size_t output buffers with max capacity
                writeSizeT(scidLenBuf, QUIC_MAX_CONN_ID_LEN)
                writeSizeT(dcidLenBuf, QUIC_MAX_CONN_ID_LEN)
                writeSizeT(tokenLenBuf, MAX_TOKEN_LEN)

                val rc =
                    api.headerInfo(
                        recvAddr,
                        recvResult.bytesReceived,
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

                val dcidLen = readSizeT(dcidLenBuf)
                val dcidKey = ConnectionIdKey.fromNative(dcidBuf, dcidLen)

                val existingDriver = connectionsByDcid[dcidKey]
                if (existingDriver != null) {
                    val sendResult = existingDriver.commands.trySend(QuicheCmd.RecvPacket(recvBuf, recvResult.bytesReceived))
                    if (sendResult.isFailure) {
                        recvBuf.freeNativeMemory()
                        // Remove ALL entries for this dead driver, not just the one we hit
                        connectionsByDcid.keys.removeAll { connectionsByDcid[it] === existingDriver }
                    }
                } else {
                    val result = acceptNewConnection(recvBuf, recvResult)
                    if (result != null) {
                        val (driver, serverScidKey) = result
                        connectionsByDcid[serverScidKey] = driver
                        connectionsByDcid[dcidKey] = driver
                        acceptedDrivers.trySend(driver)
                    }
                }
            }
        } finally {
            versionBuf.freeNativeMemory()
            typeBuf.freeNativeMemory()
            scidBuf.freeNativeMemory()
            scidLenBuf.freeNativeMemory()
            dcidBuf.freeNativeMemory()
            dcidLenBuf.freeNativeMemory()
            tokenBuf.freeNativeMemory()
            tokenLenBuf.freeNativeMemory()
        }
    }

    /**
     * Drain the cleanup channel — remove all [connectionsByDcid] entries for dead drivers.
     * Called from the receive loop coroutine only.
     */
    private fun drainCleanupChannel() {
        while (true) {
            val driver = driverCleanupCh.tryReceive().getOrNull() ?: break
            connectionsByDcid.keys.removeAll { connectionsByDcid[it] === driver }
        }
    }

    /**
     * Accept a new QUIC connection.
     * [recvBuf] ownership is consumed: it is freed after the initial packet is fed to quiche.
     */
    private fun acceptNewConnection(
        recvBuf: PlatformBuffer,
        recvResult: RecvFromResult,
    ): Pair<QuicheDriver, ConnectionIdKey>? {
        val recvAddr = recvBuf.nativeMemoryAccess!!.nativeAddress.toLong()

        val serverScid = generateScid(bufferFactory)
        val serverScidAddr = serverScid.nativeMemoryAccess!!.nativeAddress.toLong()

        val localAddr = localAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong()
        val localAddrLen = sizeOf<sockaddr_in>().toInt()

        val peerAddr = recvResult.peerAddr.rawValue.toLong()
        val peerAddrLen = recvResult.peerAddrLen.toInt()

        val conn =
            try {
                api.accept(
                    serverScidAddr,
                    QUIC_MAX_CONN_ID_LEN,
                    0L, // no retry / odcid
                    0,
                    localAddr,
                    localAddrLen,
                    peerAddr,
                    peerAddrLen,
                    config,
                )
            } catch (_: Exception) {
                serverScid.freeNativeMemory()
                recvBuf.freeNativeMemory()
                return null
            }

        if (conn.handle == 0L) {
            serverScid.freeNativeMemory()
            recvBuf.freeNativeMemory()
            return null
        }

        // Copy peer address to heap — recvResult.peerAddr points to IoUringUdpServerChannel's
        // internal buffer which will be overwritten on the next recvFrom call
        val peerAddrCopy = nativeHeap.alloc<sockaddr_storage>()
        memcpy(peerAddrCopy.ptr, recvResult.peerAddr, peerAddrLen.convert())

        // Create per-connection recvInfo (peer → local) and sendInfo
        val peerBuf = bufferFactory.allocate(peerAddrLen)
        val peerDst = peerBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        memcpy(peerDst, recvResult.peerAddr, peerAddrLen.convert())

        val recvInfo =
            api.recvInfoNew(
                peerBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                peerAddrLen,
                localAddr,
                localAddrLen,
            )
        val sendInfo = api.sendInfoNew()

        // Feed the initial packet before the driver starts
        api.connRecv(conn, recvAddr, recvResult.bytesReceived, recvInfo)
        recvBuf.freeNativeMemory()

        val udpChannel = ServerConnectionUdpChannel(serverChannel, peerAddrCopy.ptr, peerAddrLen.convert())
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

        val scidKey = ConnectionIdKey.fromNative(serverScid, QUIC_MAX_CONN_ID_LEN)
        serverScid.freeNativeMemory()
        return driver to scidKey
    }

    companion object {
        private const val MAX_DATAGRAM_SIZE = 1350
        private const val MAX_TOKEN_LEN = 256
    }
}

// --- Helpers ---

private fun writeNullTerminatedString(
    str: String,
    factory: BufferFactory,
): PlatformBuffer {
    val buf = factory.allocate(str.length + 1)
    buf.writeString(str, Charset.UTF8)
    buf.writeByte(0)
    buf.resetForRead()
    return buf
}

/** Write a native `size_t` value into a [PlatformBuffer]'s backing memory. */
private fun writeSizeT(
    buf: PlatformBuffer,
    value: Int,
) {
    buf.nativeMemoryAccess!!
        .nativeAddress
        .toCPointer<ULongVar>()!!
        .pointed
        .value = value.toULong()
}

/** Read a native `size_t` from a [PlatformBuffer]'s backing memory. */
private fun readSizeT(buf: PlatformBuffer): Int =
    buf.nativeMemoryAccess!!
        .nativeAddress
        .toCPointer<ULongVar>()!!
        .pointed
        .value
        .toInt()

/**
 * Key for connection lookup by DCID.
 *
 * Holds a managed-heap snapshot of the CID bytes (typically ≤20 bytes
 * per RFC 9000 §5.1) so the key is stable across datagram buffer
 * recycling. Equality/hash reuse the buffer library's content-based
 * helpers, eliminating the per-datagram [ByteArray] that the pre-v2
 * implementation allocated.
 */
private class ConnectionIdKey private constructor(
    private val snapshot: com.ditchoom.buffer.ReadBuffer,
) {
    override fun equals(other: Any?): Boolean = other is ConnectionIdKey && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = bufferHashCode(snapshot)

    companion object {
        fun fromNative(
            buffer: PlatformBuffer,
            length: Int,
        ): ConnectionIdKey {
            val snapshot = BufferFactory.managed().allocate(length)
            for (i in 0 until length) snapshot.writeByte(buffer.get(i))
            snapshot.resetForRead()
            return ConnectionIdKey(snapshot)
        }
    }
}

/**
 * Server-side QUIC connection backed by a [QuicheDriver].
 */
private class LinuxServerQuicConnection(
    private val driver: QuicheDriver,
    private val bufferFactory: BufferFactory,
    connectionScope: CoroutineScope,
) : QuicConnection,
    CoroutineScope by connectionScope {
    override val state: StateFlow<QuicConnectionState> = driver.state

    override suspend fun openStream(): QuicByteStream {
        try {
            val deferred = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(deferred))
            val slot = deferred.await()
            val adapter = DriverStreamAdapter(driver, slot)
            return QuicByteStream(slot.id, QuicheStreamByteStream(slot.id, adapter, bufferFactory))
        } catch (_: ClosedSendChannelException) {
            throw SocketClosedException.General("connection closed")
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
