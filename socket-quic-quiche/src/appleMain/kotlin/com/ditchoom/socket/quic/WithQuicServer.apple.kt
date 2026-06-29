@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.bufferHashCode
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.posix.AF_INET6
import platform.posix.IPPROTO_IPV6
import platform.posix.IPV6_V6ONLY
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.getsockname
import platform.posix.memcpy
import platform.posix.setsockopt
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import kotlin.concurrent.AtomicInt
import kotlin.time.Duration

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

// Darwin Kotlin/Native does not expose htons/ntohs as functions (they are C macros in <arpa/inet.h>,
// unlike Linux K/N which surfaces them in platform.posix). arm64/x86_64 macOS is little-endian, so
// host↔network byte order is a fixed byte swap. (The dual-stack bind uses in6addr_any = all zeros, so
// no htonl is needed for the wildcard address.)
private fun htons(v: UShort): UShort = (((v.toInt() and 0xFF) shl 8) or ((v.toInt() shr 8) and 0xFF)).toUShort()

private fun ntohs(v: UShort): UShort = htons(v)

/**
 * Build + bind a Linux quiche-backed [AppleQuicServer] over io_uring, returning it ready to accept.
 * The returned server owns its teardown via [AppleQuicServer.close]; the `onClose` lambda cancels
 * the per-call parent scope (previously the withQuicServer wrapper's job). Shared by
 * [QuicheEngine.bind]; the [withQuicServer] wrapper runs the block and calls `close()`.
 */
internal fun buildAppleQuicServer(
    port: Int,
    @Suppress("UNUSED_PARAMETER") host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
): QuicServer {
    // Linux bind path defaults to INADDR_ANY; `host` is accepted for API parity
    // with the JVM/Android impl but not yet wired through to inet_pton / bind.
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

        // Create & bind a DUAL-STACK IPv6 UDP socket. The Apple QUIC *client* is NWConnection-UDP, and NW
        // resolves a name like "localhost" to IPv6 (::1) by preference on Darwin; an IPv4-only server
        // would then never receive the client's packets (the handshake silently idle-times-out). Binding
        // AF_INET6 with IPV6_V6ONLY=0 accepts BOTH ::1 and IPv4 (as ::ffff:127.0.0.1 v4-mapped), so a
        // client reaching the server over either family is served. quiche reads whatever sockaddr the OS
        // reports (the CinteropQuicheApi BSD decode already handles AF_INET6 = family 30).
        val fd = socket(AF_INET6, SOCK_DGRAM, 0)
        check(fd >= 0) { "Failed to create UDP socket" }

        memScoped {
            // IPV6_V6ONLY=0 → dual-stack (also receive IPv4 as v4-mapped). Best-effort: a failure here
            // just leaves the OS default (already 0 on Darwin), so don't hard-fail on it.
            val off = alloc<IntVar>()
            off.value = 0
            setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, off.ptr, sizeOf<IntVar>().convert())

            val addr = alloc<sockaddr_in6>()
            platform.posix.memset(addr.ptr, 0, sizeOf<sockaddr_in6>().convert())
            addr.sin6_family = AF_INET6.convert()
            // sin6_addr left zeroed = in6addr_any (wildcard).
            addr.sin6_port = htons(port.toUShort())
            val bindRc = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in6>().convert())
            check(bindRc == 0) { "Failed to bind UDP socket to port $port" }
        }

        // Get assigned port. memScoped.alloc does not zero-init; we must check the
        // getsockname return value — a silent failure leaves sin6_family as garbage, which
        // SIGABRTs through Rust's std_addr_from_c panic when quiche_accept reads it.
        val boundPort =
            memScoped {
                val boundAddr = alloc<sockaddr_in6>()
                platform.posix.memset(boundAddr.ptr, 0, sizeOf<sockaddr_in6>().convert())
                val boundLen = alloc<UIntVar>()
                boundLen.value = sizeOf<sockaddr_in6>().convert()
                val rc = getsockname(fd, boundAddr.ptr.reinterpret(), boundLen.ptr)
                check(rc == 0) { "getsockname(boundPort) returned $rc" }
                check(boundAddr.sin6_family.toInt() == AF_INET6) {
                    "getsockname(boundPort) sin6_family=${boundAddr.sin6_family.toInt()} (expected AF_INET6=$AF_INET6)"
                }
                ntohs(boundAddr.sin6_port).toInt()
            }

        // Copy local address to heap buffer for recvInfo (outlives memScoped). Same
        // init/check discipline as above — this buffer is handed to quiche via api.accept
        // and api.recvInfoNew, so a garbage sin6_family here poisons every accepted connection.
        val localAddrBuf = bufferFactory.allocate(sizeOf<sockaddr_in6>().toInt())
        memScoped {
            val localAddr = alloc<sockaddr_in6>()
            platform.posix.memset(localAddr.ptr, 0, sizeOf<sockaddr_in6>().convert())
            val localLen = alloc<UIntVar>()
            localLen.value = sizeOf<sockaddr_in6>().convert()
            val rc = getsockname(fd, localAddr.ptr.reinterpret(), localLen.ptr)
            check(rc == 0) { "getsockname(localAddr) returned $rc" }
            check(localAddr.sin6_family.toInt() == AF_INET6) {
                "getsockname(localAddr) sin6_family=${localAddr.sin6_family.toInt()} (expected AF_INET6=$AF_INET6)"
            }
            val dst = localAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
            memcpy(dst, localAddr.ptr, sizeOf<sockaddr_in6>().convert())
        }

        val server =
            AppleQuicServer(
                api = api,
                config = config,
                serverChannel = AppleUdpServerChannel(fd),
                boundPort = boundPort,
                localAddrBuf = localAddrBuf,
                bufferFactory = bufferFactory,
                scope = parentScope,
                keepAliveInterval = quicOptions.keepAliveInterval,
                // server.close() frees config + drivers; the per-call parent scope is the server's
                // to cancel last, so the withQuicServer wrapper stays a plain block + close().
                onClose = { parentScope.cancel() },
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
 * Linux QUIC server. Central receive loop uses [AppleUdpServerChannel.recvFrom]
 * to parse QUIC headers and route packets by DCID to the appropriate [QuicheDriver].
 *
 * Mirrors [JvmQuicServer] architecture — each connection driven by its own [QuicheDriver],
 * no shared mutexes, zero-copy packet delivery.
 */
private class AppleQuicServer(
    private val api: QuicheApi,
    private val config: QuicheConfig,
    private val serverChannel: AppleUdpServerChannel,
    private val boundPort: Int,
    private val localAddrBuf: PlatformBuffer,
    private val bufferFactory: BufferFactory,
    private val scope: CoroutineScope,
    private val keepAliveInterval: Duration? = null,
    // Per-call lifecycle teardown wired by buildAppleQuicServer (cancel the parent scope). Invoked
    // last by close(); null for any direct-construction test that owns the scope externally.
    private val onClose: (() -> Unit)? = null,
) : QuicServer {
    override val port: Int get() = boundPort

    private val connectionsByDcid = mutableMapOf<ConnectionIdKey, QuicheDriver>()
    private val acceptedDrivers = Channel<QuicheDriver>(Channel.UNLIMITED)

    /**
     * Passive-migration support (mirrors [JvmQuicServer]): one server socket sees a client's
     * source change after it migrates (RFC 9000 §9). quiche needs the *actual* per-datagram
     * source as recv_info.from to recognise the new path, so cache one recv_info per distinct
     * source (its `to` is the server's fixed local addr in [localAddrBuf]).
     *
     * Bounded ([maxPeerRecvInfos]) + reference-counted, identical to the JVM server: the cached
     * pointer is handed to a driver over an UNLIMITED command channel, so eviction frees only an
     * entry whose [CachedRecvInfo.inFlight] is zero (released via [QuicheCmd.RecvPacket.
     * onRecvInfoConsumed]). Access order is maintained manually (remove+reinsert on hit) since
     * K/N's LinkedHashMap has no accessOrder constructor. Receive-loop coroutine is the only
     * writer; [inFlight] is the only field a driver coroutine touches.
     */
    private val maxPeerRecvInfos = 256
    private val peerRecvInfos = LinkedHashMap<PathKey, CachedRecvInfo>()

    private class CachedRecvInfo(
        val info: QuicheRecvInfo,
        val fromBuf: PlatformBuffer,
    ) {
        val inFlight = AtomicInt(0)
    }

    private fun recvInfoFor(
        peerAddr: Long,
        peerAddrLen: Int,
    ): CachedRecvInfo {
        val key = api.decodePathKey(peerAddr)
        peerRecvInfos.remove(key)?.let {
            peerRecvInfos[key] = it // bump to most-recently-used
            return it
        }
        // Miss: pin a copy of this source's sockaddr (recvFrom's buffer is reused next call) and
        // build a recv_info from = source, to = the server's fixed local addr.
        val fromBuf = bufferFactory.allocate(peerAddrLen)
        val dst = fromBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        memcpy(dst, peerAddr.toCPointer<ByteVar>()!!, peerAddrLen.convert())
        val info =
            api.recvInfoNew(
                fromBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                peerAddrLen,
                localAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                sizeOf<sockaddr_in6>().toInt(),
            )
        val cached = CachedRecvInfo(info, fromBuf)
        peerRecvInfos[key] = cached
        evictIdlePeerRecvInfo()
        return cached
    }

    /** Free the least-recently-used cached recv_info whose [inFlight] is zero once over cap. */
    private fun evictIdlePeerRecvInfo() {
        if (peerRecvInfos.size <= maxPeerRecvInfos) return
        val iterator = peerRecvInfos.entries.iterator() // insertion order == access order (maintained above)
        while (iterator.hasNext()) {
            val cached = iterator.next().value
            if (cached.inFlight.value == 0) {
                api.recvInfoFree(cached.info)
                cached.fromBuf.freeNativeMemory()
                iterator.remove()
                return
            }
        }
    }

    /**
     * Queue for drivers that need their [connectionsByDcid] entries removed.
     * Connection handlers add drivers here after close; the receive loop drains it.
     * This keeps all map mutations on the receive loop coroutine.
     */
    private val driverCleanupCh = Channel<QuicheDriver>(Channel.UNLIMITED)

    /**
     * Queue of spare-SCID registrations from drivers' [QuicheDriver.onScidIssued] (fired on a
     * driver coroutine when it issues spare CIDs at establishment). The receive loop drains it
     * into [connectionsByDcid] so a migrating peer's new DCID (a server-issued SCID) routes to the
     * right driver — without this, active migration fails path validation. Mirrors [JvmQuicServer].
     */
    private val scidRegistrationCh = Channel<Pair<ConnectionIdKey, QuicheDriver>>(Channel.UNLIMITED)

    @kotlin.concurrent.Volatile
    private var closed = false

    private val receiveJob = scope.launch(Dispatchers.Default) { receiveLoop() }

    override suspend fun connections(handler: suspend QuicScope.() -> Unit) =
        // Structured concurrency: handler lifetime is bound to this connections()
        // invocation. Cancelling the caller cancels every in-flight handler — see
        // commonJvmWithQuicServer's JvmQuicServer.connections() for the long
        // comment on why this matters (previously handlers were orphaned onto the
        // engine's scope and surfaced as a CI hang in
        // JvmQuicServerTestSuite.rapidBindConnectCloseCyclesAreClean).
        kotlinx.coroutines.coroutineScope {
            for (driver in acceptedDrivers) {
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
        // Drivers destroyed (their drain released every in-flight ref) — free the per-source
        // recv_info cache. Each recv_info before its `from` buffer; localAddrBuf (the shared `to`)
        // is freed last, below.
        for (cached in peerRecvInfos.values) {
            api.recvInfoFree(cached.info)
            cached.fromBuf.freeNativeMemory()
        }
        peerRecvInfos.clear()
        api.configFree(config)
        acceptedDrivers.close()
        scidRegistrationCh.close()
        serverChannel.freeBuffers()
        localAddrBuf.freeNativeMemory()
        // Cancel the per-call parent scope last (buildAppleQuicServer wires this); previously the
        // withQuicServer wrapper did it after server.close(). receiveJob/handlers are its children.
        onClose?.invoke()
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
                // Register spare SCIDs issued by drivers so a migrating peer's new DCID routes.
                drainScidRegistrations()

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
                    // Per-source recv_info so quiche sees a migrated client's new source as a new
                    // path. Hold an in-flight ref so the cache can't evict+free it while queued.
                    val cached = recvInfoFor(recvResult.peerAddr.rawValue.toLong(), recvResult.peerAddrLen.toInt())
                    cached.inFlight.incrementAndGet()
                    val sendResult =
                        existingDriver.commands.trySend(
                            QuicheCmd.RecvPacket(
                                recvBuf,
                                recvResult.bytesReceived,
                                recvInfoOverride = cached.info,
                                onRecvInfoConsumed = { cached.inFlight.decrementAndGet() },
                            ),
                        )
                    if (sendResult.isFailure) {
                        cached.inFlight.decrementAndGet()
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
     * Register spare SCIDs issued by drivers — maps each to its driver so a migrating peer's new
     * DCID routes correctly. Receive-loop coroutine only. A registration for an already-removed
     * driver is harmless: [drainCleanupChannel] runs first each iteration.
     */
    private fun drainScidRegistrations() {
        while (true) {
            val (key, driver) = scidRegistrationCh.tryReceive().getOrNull() ?: break
            connectionsByDcid[key] = driver
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
        val localAddrLen = sizeOf<sockaddr_in6>().toInt()

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

        // Copy peer address to heap — recvResult.peerAddr points to AppleUdpServerChannel's
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

        val udpChannel = ServerConnectionUdpChannel(serverChannel, peerAddrCopy.ptr, peerAddrLen.convert(), bufferFactory)
        // Self-reference for onScidIssued: the driver doesn't exist when we build the callback, so
        // capture it via this holder, set right after construction. The callback only fires at
        // establishment (well after this), so the holder is always populated by then.
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
                onScidIssued = { scid, len ->
                    // Snapshot the CID (scid is freed right after this returns) and hand the
                    // registration to the receive loop, which owns connectionsByDcid.
                    driverRef?.let { d -> scidRegistrationCh.trySend(ConnectionIdKey.fromNative(scid, len) to d) }
                },
            )
        driverRef = driver

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
private class AppleServerQuicConnection(
    private val driver: QuicheDriver,
    override val bufferFactory: BufferFactory,
    connectionScope: CoroutineScope,
) : QuicConnection,
    CoroutineScope by connectionScope {
    override val state: StateFlow<QuicConnectionState> = driver.state

    private val datagramAdapter = DriverDatagramAdapter(driver, bufferFactory)

    override suspend fun openStream(): QuicByteStream = open(unidirectional = false)

    override suspend fun openUniStream(): QuicByteStream = open(unidirectional = true)

    private suspend fun open(unidirectional: Boolean): QuicByteStream {
        try {
            val deferred = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(deferred, unidirectional))
            val slot = deferred.await()
            val adapter = DriverStreamAdapter(driver, slot)
            return QuicByteStream(slot.id, QuicheStreamByteStream(slot.id, adapter, bufferFactory))
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
