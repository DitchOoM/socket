@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.appleSockAddrLayout
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * Build + bind an Apple quiche-backed [AppleQuicServer] over the `:socket-udp` dual-stack POSIX UDP
 * datagram channel (Phase 6 adapter-first cutover). The dual-stack (`::` + `IPV6_V6ONLY=0`) bind lives
 * in `UdpSocket.bind` now, so a client reaching the server over IPv4 or IPv6 is served. The returned
 * server owns its teardown via [AppleQuicServer.close]; the `onClose` lambda cancels the per-call
 * parent scope. Shared by [QuicheEngine.bind]; the [withQuicServer] wrapper runs the block + `close()`.
 */
internal suspend fun buildAppleQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    // Determinism seams (RFC_DETERMINISTIC_SIMULATION.md §3.1) — production defaults are
    // byte-identical to the pre-seam behaviour; the sim harness injects its own.
    tuning: QuicheDriverTuning = QuicheDriverTuning(),
): QuicServer {
    val api: QuicheApi = CinteropQuicheApi
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.Default)
    var bound = false
    try {
        val bufferFactory = BufferFactory.network()

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

        applyQuicOptions(quicOptions, AppleQuicConfigCalls(config.handle.toCPointer()!!))

        // Bind the shared server socket via :socket-udp (dual-stack by default) with a QUIC-sized receive
        // staging buffer. One channel serves every accepted connection; egress is a thin
        // ServerConnectionUdpChannel over it.
        val channel = UdpSocket.bind(host, port, receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE)
        val localAddress = channel.localAddress ?: error("bound server channel has no local address")

        val server =
            AppleQuicServer(
                api = api,
                config = config,
                channel = channel,
                localAddress = localAddress,
                codec = SocketAddressCodec(appleSockAddrLayout),
                bufferFactory = bufferFactory,
                parentScope = parentScope,
                keepAliveInterval = quicOptions.keepAliveInterval,
                // server.close() frees config + drivers; the per-call parent scope is the server's
                // to cancel last, so the withQuicServer wrapper stays a plain block + close().
                onClose = { parentScope.cancel() },
                tuning = tuning,
            )
        bound = true
        return server
    } finally {
        // On success the server owns parentScope teardown via onClose; release here only on
        // a bind failure before the server took ownership.
        if (!bound) parentScope.cancel()
    }
}

/**
 * Apple QUIC server over the `:socket-udp` [DatagramChannel]. Structurally mirrors `JvmQuicServer` /
 * `LinuxQuicServer`: a dedicated reader coroutine confines the channel's `receive()` and feeds a central
 * loop that routes each datagram by DCID to its [QuicheDriver] (or accepts a new connection). All
 * connection-lifecycle bookkeeping lives in the shared [ServerConnectionRegistry] (the #179 recv_info-UAF
 * invariants are fix-once).
 */
private class AppleQuicServer(
    private val api: QuicheApi,
    private val config: QuicheConfig,
    private val channel: DatagramChannel,
    private val localAddress: SocketAddress,
    private val codec: SocketAddressCodec,
    private val bufferFactory: BufferFactory,
    parentScope: CoroutineScope,
    private val keepAliveInterval: Duration? = null,
    // Per-call lifecycle teardown wired by buildAppleQuicServer (cancel the parent scope). Invoked
    // last by close(); null for any direct-construction test that owns the scope externally.
    private val onClose: (() -> Unit)? = null,
    // Determinism seams forwarded to every accepted connection's driver (RFC_DETERMINISTIC_SIMULATION.md §3.1).
    private val tuning: QuicheDriverTuning = QuicheDriverTuning(),
) : QuicServer {
    override val port: Int get() = localAddress.port

    // Child scope of the per-call parent — cancelling it takes down every handler coroutine spawned
    // via connections() plus the receive loop.
    private val serverJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + serverJob)

    /** Connection-lifecycle bookkeeping shared with the other platform servers (see [ServerConnectionRegistry]). */
    private val registry = ServerConnectionRegistry<SocketAddress>(api)

    /**
     * PathKey→peer resolution for the egress channels (sendInfo.to → real send target, no address
     * reconstruction — RFC §4). Single-writer: only the receive loop mutates it (on cache miss / evict),
     * plus the close sweep after the loop has joined; readers are the driver egress coroutines. K/N has no
     * concurrent map, so it is a `@Volatile` **immutable** map — the writer publishes a fresh copy and
     * readers see a consistent snapshot. Entries are removed when their recv_info cache entry is freed.
     */
    @kotlin.concurrent.Volatile
    private var peersByPathKey: Map<PathKey, SocketAddress> = emptyMap()

    /** Prompt-wake for the receive loop on a routing-table change (see JvmQuicServer.wakeups). */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /**
     * Recv buffer pool — each datagram is acquired here, ownership transfers to the connection's driver,
     * and every free-path recycles it back instead of malloc/freeing per packet. [bufferFactory] is a
     * leaf factory — the pool is built *from* it (never pass an already-pooled factory).
     */
    private val recvBufPool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 64,
            defaultBufferSize = MAX_DATAGRAM_SIZE,
            factory = bufferFactory,
        )

    /** The shared recv_info `to` sockaddr (the server's fixed local addr), encoded lazily; freed in close(). */
    private var serverLocalSockAddr: EncodedSockAddr? = null

    @kotlin.concurrent.Volatile
    private var closed = false

    private val receiveJob = scope.launch(Dispatchers.Default) { receiveLoop() }

    override suspend fun connections(handler: suspend QuicScope.() -> Unit) =
        coroutineScope {
            for (driver in registry.acceptedDrivers) {
                launch(Dispatchers.Default) {
                    val connJob = SupervisorJob(coroutineContext[Job])
                    val connScope = CoroutineScope(coroutineContext + connJob)
                    val conn = AppleServerQuicConnection(driver, bufferFactory, connScope)
                    try {
                        conn.state.first { it !is QuicConnectionState.Handshaking }
                        if (conn.state.value is QuicConnectionState.Established) {
                            conn.handler()
                        }
                    } finally {
                        conn.close()
                        registry.enqueueCleanup(driver)
                        wakeups.trySend(Unit) // drain the routing table now, not on the next datagram
                        connJob.cancel()
                    }
                }
            }
        }

    override suspend fun close() {
        if (closed) return
        closed = true
        // Closing the :socket-udp channel makes any in-flight recvfrom on the POSIX UDP socket fail, so
        // the reader's receive() returns Closed, the loop exits, and receiveJob completes.
        channel.close()
        receiveJob.join()
        // receiveJob.join() guarantees no new drivers are added/routed and nothing else touches the
        // registry. Destroy+join EVERY live driver (a superset of the routing table) before freeing the
        // per-source recv_info cache — the #179 UAF invariant.
        registry.reapAllDriversAndFreeRecvInfoCache()
        serverLocalSockAddr?.free()
        serverLocalSockAddr = null
        recvBufPool.clear()
        api.configFree(config)
        registry.closeChannels()
        serverJob.cancel()
        onClose?.invoke()
    }

    private suspend fun receiveLoop() =
        coroutineScope {
            // Header parsing scratch buffers (reused across iterations)
            val versionBuf = bufferFactory.allocate(4)
            val typeBuf = bufferFactory.allocate(1)
            val scidBuf = bufferFactory.allocate(QUIC_MAX_CONN_ID_LEN)
            val scidLenBuf = bufferFactory.allocate(8)
            val dcidBuf = bufferFactory.allocate(QUIC_MAX_CONN_ID_LEN)
            val dcidLenBuf = bufferFactory.allocate(8)
            val tokenBuf = bufferFactory.allocate(MAX_TOKEN_LEN)
            val tokenLenBuf = bufferFactory.allocate(8)

            val inbound = Channel<DatagramReadResult>(Channel.RENDEZVOUS)
            val reader =
                launch(Dispatchers.Default) {
                    try {
                        while (true) {
                            val r =
                                try {
                                    channel.receive()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    if (closed) break else continue
                                }
                            inbound.send(r)
                            if (r is DatagramReadResult.Closed) break
                        }
                    } catch (_: CancellationException) {
                    } finally {
                        inbound.close()
                    }
                }

            val wake = Any()
            val readerDone = Any()
            try {
                loop@ while (!closed) {
                    val item =
                        select<Any?> {
                            inbound.onReceiveCatching { res -> res.getOrNull() ?: readerDone }
                            wakeups.onReceive { wake }
                        }
                    // Register spare SCIDs / drop closed drivers BEFORE routing anything. Receive-loop only.
                    registry.drainRoutingQueues()

                    val datagram =
                        when (item) {
                            wake -> continue@loop
                            readerDone -> break@loop
                            is DatagramReadResult.Closed -> break@loop
                            is DatagramReadResult.Received -> item.datagram
                            else -> continue@loop
                        }
                    if (closed) {
                        datagram.payload.freeNativeMemory()
                        break@loop
                    }

                    val peer = datagram.peer
                    val received = datagram.payload.remaining()
                    if (received <= 0) {
                        datagram.payload.freeNativeMemory()
                        continue@loop
                    }

                    // Copy into a pooled buffer (ownership transfers to the driver, freed back to the pool
                    // once quiche_conn_recv copies the bytes) and free the channel-owned payload (B2 copy).
                    val recvBuf = recvBufPool.allocate(MAX_DATAGRAM_SIZE)
                    recvBuf.position(0)
                    recvBuf.write(datagram.payload)
                    datagram.payload.freeNativeMemory()
                    val recvAddr = recvBuf.nativeMemoryAccess!!.nativeAddress.toLong()

                    writeSizeT(scidLenBuf, QUIC_MAX_CONN_ID_LEN)
                    writeSizeT(dcidLenBuf, QUIC_MAX_CONN_ID_LEN)
                    writeSizeT(tokenLenBuf, MAX_TOKEN_LEN)

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
                        continue@loop
                    }

                    val dcidLen = readSizeT(dcidLenBuf)
                    val dcidKey = ConnectionIdKey.from(dcidBuf, dcidLen)

                    val existingDriver = registry.driverForDcid(dcidKey)
                    if (existingDriver != null) {
                        val cached =
                            registry.lookupRecvInfo(peer) ?: run {
                                val local =
                                    serverLocalSockAddr ?: codec.encodeToNative(localAddress, bufferFactory).also {
                                        serverLocalSockAddr = it
                                    }
                                val from = codec.encodeToNative(peer, bufferFactory)
                                val info = api.recvInfoNew(from.address, from.length, local.address, local.length)
                                // Record this source's PathKey→peer (single-writer volatile map) so a reply
                                // quiche routes here resolves to the real send target; removed on cache free.
                                val fromKey = api.decodePathKey(from.address)
                                peersByPathKey = peersByPathKey + (fromKey to peer)
                                registry.putRecvInfo(peer, info) {
                                    from.free()
                                    peersByPathKey = peersByPathKey - fromKey
                                }
                            }
                        cached.inFlight.incrementAndGet()
                        val sendResult =
                            existingDriver.commands.trySend(
                                QuicheCmd.RecvPacket(
                                    recvBuf,
                                    received,
                                    recvInfoOverride = cached.info,
                                    onRecvInfoConsumed = { cached.inFlight.decrementAndGet() },
                                ),
                            )
                        if (sendResult.isFailure) {
                            cached.inFlight.decrementAndGet()
                            recvBuf.freeNativeMemory()
                            registry.deRouteDriver(existingDriver)
                        }
                    } else {
                        val accepted = acceptNewConnection(recvBuf, received, peer)
                        if (accepted != null) {
                            val (driver, serverScidKey) = accepted
                            registry.routeDriver(serverScidKey, driver)
                            registry.routeDriver(dcidKey, driver)
                            registry.acceptedDrivers.trySend(driver)
                        }
                    }
                }
            } finally {
                reader.cancel()
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
     * Accept a new QUIC connection. [recvBuf] ownership is consumed: it is freed after the initial
     * packet is fed to quiche.
     */
    private fun acceptNewConnection(
        recvBuf: PlatformBuffer,
        received: Int,
        peer: SocketAddress,
    ): Pair<QuicheDriver, ConnectionIdKey>? {
        val recvAddr = recvBuf.nativeMemoryAccess!!.nativeAddress.toLong()

        val serverScid = generateScid(bufferFactory, tuning.random)
        val serverScidAddr = serverScid.nativeMemoryAccess!!.nativeAddress.toLong()

        val peerSockAddr = codec.encodeToNative(peer, bufferFactory)
        val localSockAddr = codec.encodeToNative(localAddress, bufferFactory)

        val conn =
            try {
                api.accept(
                    serverScidAddr,
                    QUIC_MAX_CONN_ID_LEN,
                    0L, // no retry / odcid
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
            api.recvInfoNew(peerSockAddr.address, peerSockAddr.length, localSockAddr.address, localSockAddr.length)
        val sendInfo = api.sendInfoNew()

        // Feed the initial packet before the driver starts — safe, driver not yet running.
        api.connRecv(conn, recvAddr, received, recvInfo)
        recvBuf.freeNativeMemory()

        // Per-connection egress over the shared server socket. fixedPeerKey is this peer's PathKey (what
        // quiche echoes as sendInfo.to for the un-migrated path); peersByPathKey resolves a migrated source.
        val udpChannel =
            ServerConnectionUdpChannel(
                channel = channel,
                fixedPeer = peer,
                fixedPeerKey = api.decodePathKey(peerSockAddr.address),
                peerFor = { peersByPathKey[it] },
            )
        // Self-reference for onScidIssued: the driver doesn't exist when we build the callback, so
        // capture it via this holder, set right after construction.
        var driverRef: QuicheDriver? = null
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
                keepAliveInterval = keepAliveInterval,
                clock = tuning.clock,
                driverContext = tuning.driverContext,
                random = tuning.random,
                recorder = tuning.recorderFactory(),
                // recvInfo holds raw pointers into peerSockAddr/localSockAddr; keep them reachable for the
                // driver's life and freed on teardown so recv_info.from/to can never dangle.
                onCleanup = {
                    driverRef?.let { registry.untrackLiveDriver(it) }
                    peerSockAddr.free()
                    localSockAddr.free()
                },
                onScidIssued = { scid, len ->
                    // Snapshot the CID (scid is freed right after this returns) and hand the registration
                    // to the receive loop; poke it to drain promptly so a migrating peer's new DCID routes.
                    driverRef?.let { d ->
                        registry.enqueueScidRegistration(ConnectionIdKey.from(scid, len), d)
                        wakeups.trySend(Unit)
                    }
                },
            )
        driverRef = driver

        // Ledger the driver BEFORE starting its run loop so close() can never observe a started-but-
        // untracked driver (additions happen only on the receive-loop coroutine).
        registry.trackLiveDriver(driver)
        driver.start(scope)

        val scidKey = ConnectionIdKey.from(serverScid, QUIC_MAX_CONN_ID_LEN)
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
 * Server-side QUIC connection backed by a [QuicheDriver].
 */
private class AppleServerQuicConnection(
    private val driver: QuicheDriver,
    override val bufferFactory: BufferFactory,
    connectionScope: CoroutineScope,
) : QuicConnection,
    CoroutineScope by connectionScope {
    override val state: StateFlow<QuicConnectionState> = driver.state

    private val datagramAdapter = DriverDatagramAdapter(driver)

    override suspend fun openStream(): QuicByteStream = open(unidirectional = false)

    override suspend fun openUniStream(): QuicByteStream = open(unidirectional = true)

    private suspend fun open(unidirectional: Boolean): QuicByteStream {
        try {
            val deferred = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(deferred, unidirectional))
            val slot = deferred.await()
            val adapter = DriverStreamAdapter(driver, slot)
            return QuicByteStream(slot.id, QuicheStreamByteStream(slot.id, adapter, driver.streamReadPool))
        } catch (_: ClosedSendChannelException) {
            throw QuicCloseException(driver.closeReasonOr(QuicError.NoError), "connection closed")
        }
    }

    override suspend fun acceptStream(): QuicByteStream = driver.incomingStreams.receive()

    override fun streams(): Flow<QuicByteStream> = driver.incomingStreams.consumeAsFlow()

    override suspend fun sendDatagram(buffer: ReadBuffer) = datagramAdapter.sendDatagram(buffer)

    override suspend fun receiveDatagram(): DatagramReceiveResult = datagramAdapter.receiveDatagram()

    override fun datagrams(): Flow<ReadBuffer> = datagramAdapter.datagrams()

    override fun maxDatagramSize(): MaxDatagramSize = datagramAdapter.maxDatagramSize()

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
