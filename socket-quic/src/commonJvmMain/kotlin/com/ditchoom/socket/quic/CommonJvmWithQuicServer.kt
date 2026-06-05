package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.bufferHashCode
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.buffer.use
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * Shared JVM/Android [withQuicServer] implementation backed by quiche +
 * [DatagramChannel]. Owns the per-call scope + native resources for the
 * duration of [block]; closes the server (UDP socket, drivers, handler
 * coroutines) before returning.
 *
 * Lives in `commonJvmMain` so both `jvmMain` and `androidMain` actuals
 * delegate to it.
 */
internal suspend fun <R> commonJvmWithQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    @Suppress("UNUSED_PARAMETER") timeout: Duration,
    block: suspend QuicServer.() -> R,
): R {
    val api: QuicheApi = loadQuicheApi()
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.IO)
    try {
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

        val server = JvmQuicServer(api, config, channel, localAddr, bufferFactory, parentScope)
        try {
            return server.block()
        } finally {
            server.close()
        }
    } finally {
        parentScope.cancel()
    }
}

internal fun writeNullTerminatedString(
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
    val bb = (buf.unwrapFully() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
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
    parentScope: CoroutineScope,
) : QuicServer {
    override val port: Int get() = localAddr.port

    // Child scope of the per-call parent — cancelling it takes down every handler
    // coroutine spawned via connections() plus the receive loop. Without this,
    // handlers launched on the parent scope leak past server.close() and pile
    // up on Dispatchers.IO across successive tests.
    private val serverJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + serverJob)

    private val connectionsByDcid = mutableMapOf<ConnectionIdKey, QuicheDriver>()
    private val acceptedDrivers = Channel<QuicheDriver>(Channel.UNLIMITED)

    /**
     * Recv buffer pool — each incoming UDP datagram needs a fresh MAX_DATAGRAM_SIZE
     * (1350-byte) buffer, and ownership transfers to the connection's driver which
     * frees it after quiche_conn_recv copies the bytes internally. PooledBuffer's
     * `freeNativeMemory()` returns to the pool, so all existing free-paths
     * (driver command execution, receive-loop error branches, destroy drain) feed
     * the pool without code changes.
     *
     * MultiThreaded mode: receive loop acquires (Selector thread); drivers release
     * (per-connection coroutine, can hop dispatchers under Dispatchers.IO).
     * maxPoolSize=64 → ~87 KB cached (64 × 1350) — caps steady-state RSS while
     * covering typical concurrent-driver counts.
     *
     * Ownership invariant: [bufferFactory] is a **leaf** factory per the
     * `ConnectionOptions.bufferFactory` contract — this pool is built *from* it
     * here. Never pass an already-pooled factory as `factory`: wrapping a pool
     * in a pool is the `80575c1` double-wrap regression. A `BufferPool` whose
     * leaf `factory` is itself a `BufferPool` makes `freeNativeMemory()` return
     * the buffer to the inner pool while the outer pool's accounting still
     * counts it, so neither pool reclaims correctly and the cap stops bounding
     * RSS. Keep the leaf-factory-in, pool-built-here shape.
     */
    private val recvBufPool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 64,
            defaultBufferSize = MAX_DATAGRAM_SIZE,
            factory = bufferFactory,
        )

    /**
     * Thread-safe queue for drivers that need their [connectionsByDcid] entries removed.
     * Connection handlers add drivers here after close; the receive loop drains it.
     * This keeps all map mutations on the receive loop thread — no ConcurrentHashMap needed.
     */
    private val driverCleanupQueue = ConcurrentLinkedQueue<QuicheDriver>()

    /**
     * Thread-safe queue of spare-SCID registrations from drivers' [QuicheDriver.onScidIssued]
     * (fired on a driver coroutine). The receive loop drains it into [connectionsByDcid] so a
     * migrating peer's new DCID routes to the right driver. Mirrors [driverCleanupQueue]: keeps
     * all map mutations on the receive-loop thread (no concurrent map needed).
     */
    private val scidRegistrationQueue = ConcurrentLinkedQueue<Pair<ConnectionIdKey, QuicheDriver>>()

    /**
     * Passive-migration support: one server socket sees a client's source address change
     * after the client migrates (RFC 9000 §9). quiche needs the *actual* per-datagram source
     * as recv_info.from to recognise the new path, so we cache one recv_info per distinct
     * source (its `to` is the server's fixed local addr, shared via [serverLocalSockAddr]).
     *
     * Bounded LRU ([maxPeerRecvInfos]) so a peer spraying spoofed source addresses on an
     * established connection can't grow native memory without bound (the cache miss path
     * allocates a recv_info + sockaddr per distinct source). Access-ordered so eviction
     * targets idle sources. A cached recv_info pointer is handed to a driver via an UNLIMITED
     * command channel, so a lagging driver may still hold it after the source goes idle;
     * [CachedRecvInfo.inFlight] tracks outstanding references (released by the driver via
     * [QuicheCmd.RecvPacket.onRecvInfoConsumed]) so eviction never frees one that's still in use.
     *
     * Touched only on the receive-loop thread (single writer); [inFlight] is the only field a
     * driver thread mutates. Freed in [close] after every driver is destroyed.
     */
    private val maxPeerRecvInfos = 256

    // accessOrder = true → iteration/eviction visits least-recently-used first.
    private val peerRecvInfos =
        LinkedHashMap<InetSocketAddress, CachedRecvInfo>(16, 0.75f, true)
    private var serverLocalSockAddr: NativeSockAddr? = null

    private class CachedRecvInfo(
        val info: QuicheRecvInfo,
        val from: NativeSockAddr,
    ) {
        /** Packets handed to a driver but not yet consumed; guards against evict-while-in-use. */
        val inFlight = AtomicInteger(0)
    }

    private fun recvInfoFor(source: InetSocketAddress): CachedRecvInfo {
        peerRecvInfos[source]?.let { return it } // get() promotes to most-recently-used
        val local =
            serverLocalSockAddr ?: localAddr.toNativeSockAddr(bufferFactory).also {
                serverLocalSockAddr = it
            }
        val from = source.toNativeSockAddr(bufferFactory)
        val info = api.recvInfoNew(from.address, from.length, local.address, local.length)
        val cached = CachedRecvInfo(info, from)
        peerRecvInfos[source] = cached
        evictIdlePeerRecvInfo()
        return cached
    }

    /**
     * Free the least-recently-used cached recv_info whose [CachedRecvInfo.inFlight] is zero once
     * over [maxPeerRecvInfos]. If every over-cap entry is still in flight (pathological), skip
     * rather than risk a use-after-free — we briefly exceed the cap and the next miss retries.
     * Receive-loop thread only.
     */
    private fun evictIdlePeerRecvInfo() {
        if (peerRecvInfos.size <= maxPeerRecvInfos) return
        val iterator = peerRecvInfos.entries.iterator() // access order: eldest first
        while (iterator.hasNext()) {
            val cached = iterator.next().value
            if (cached.inFlight.get() == 0) {
                api.recvInfoFree(cached.info)
                cached.from.free()
                iterator.remove()
                return
            }
        }
    }

    @Volatile private var closed = false

    @Volatile private var receiveSelector: Selector? = null
    private val receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }

    override suspend fun connections(handler: suspend QuicScope.() -> Unit) =
        // Bind handler lifetime to the caller's coroutine — cancelling the coroutine
        // that called connections() must cancel each in-flight handler. Previously
        // each handler was launched on the engine's own `scope`, so a caller-side
        // cancel only broke the for-loop and left handlers running indefinitely on
        // the engine scope — invisible to the caller, but deadlocked any test that
        // suspended in the handler (e.g. awaitCancellation()) and then expected
        // cancel to clean up. coroutineScope { … } suspends until every launched
        // child returns; structured concurrency makes the lifetime explicit.
        kotlinx.coroutines.coroutineScope {
            for (driver in acceptedDrivers) {
                launch(Dispatchers.IO) {
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
                        driverCleanupQueue.add(driver)
                        receiveSelector?.wakeup()
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
        // Drivers destroyed (their drain released every in-flight ref) — free the per-source
        // recv_info cache. Free each recv_info before the `from` sockaddr it points at, then the
        // shared `to` sockaddr last (every recv_info pointed at it).
        for (cached in peerRecvInfos.values) {
            api.recvInfoFree(cached.info)
            cached.from.free()
        }
        peerRecvInfos.clear()
        serverLocalSockAddr?.free()
        serverLocalSockAddr = null
        // Drivers have drained — safe to free cached recv buffers. Any release
        // after this point would silently repopulate the pool (benign, but
        // buffers would leak until server GC), which is why this follows the
        // destroy loop.
        recvBufPool.clear()
        api.configFree(config)
        acceptedDrivers.close()
        // Cancel handler coroutines spawned via connections() — they're
        // children of serverJob. Non-blocking: the guarantee we need is
        // asserted by JvmQuicServerLifecycleTests (no coroutines outlive
        // server.close()).
        serverJob.cancel()
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
            // Guard against the race where close() runs before this coroutine has been
            // scheduled. The channel would already be closed and register() would throw.
            if (closed) return
            try {
                channel.register(selector, SelectionKey.OP_READ)
            } catch (_: java.nio.channels.ClosedChannelException) {
                return
            }

            while (!closed) {
                // runInterruptible: see NioUdpChannel.receive — the same
                // "suspend method calling blocking Selector.select" trap.
                // Without it, coroutine cancellation can't reach a thread
                // parked in EPoll.wait; if close()'s `selector.wakeup() +
                // channel.close()` race against our entry into select(),
                // the receive loop stalls and `receiveJob.join()` in close()
                // hangs forever. With runInterruptible, scope cancel ⇒
                // thread interrupt ⇒ select() returns ⇒ loop exits.
                // Dispatcher routes through quicBlockingDispatcher — virtual
                // threads on JDK 21+, Dispatchers.IO fallback otherwise.
                kotlinx.coroutines.runInterruptible(quicBlockingDispatcher) { selector.select() }
                if (closed) break
                selector.selectedKeys().clear()

                // Remove entries for drivers that have been closed by connection handlers,
                // and register spare SCIDs issued by drivers. Both run on the receive loop
                // thread — sole writer to connectionsByDcid.
                drainCleanupQueue()
                drainScidRegistrations()

                // Drain all available packets after select returns
                while (true) {
                    // Acquire a buffer from the per-server pool — ownership transfers to driver
                    // (zero-copy). The driver's freeNativeMemory() releases back to the pool.
                    val recvBuf = recvBufPool.allocate(MAX_DATAGRAM_SIZE)
                    val recvByteBuffer = (recvBuf.unwrapFully() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
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
                        // Zero-copy: transfer buffer ownership to driver. The per-source recv_info
                        // lets quiche see the real datagram origin so a migrated client's new source
                        // is recognised as a new path (passive migration). Hold an in-flight ref so
                        // the cache can't evict+free this recv_info while the driver still has it queued.
                        val cached = recvInfoFor(peerInetAddr)
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
                            // Not delivered → onRecvInfoConsumed won't fire; release the ref here.
                            cached.inFlight.decrementAndGet()
                            recvBuf.freeNativeMemory()
                            // Remove ALL entries for this dead driver, not just the one we hit
                            connectionsByDcid.entries.removeIf { it.value === existingDriver }
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
     * Drain the cleanup queue — remove all [connectionsByDcid] entries for dead drivers.
     * Called from the receive loop thread only.
     */
    private fun drainCleanupQueue() {
        while (true) {
            val driver = driverCleanupQueue.poll() ?: break
            connectionsByDcid.entries.removeIf { it.value === driver }
        }
    }

    /**
     * Register spare SCIDs issued by drivers — maps each to its driver so a migrating peer's
     * new DCID routes correctly. Called from the receive loop thread only. A registration for an
     * already-removed driver is harmless: [drainCleanupQueue] runs first each wakeup, and the
     * connection handler's cleanup re-adds to [driverCleanupQueue] which is drained next time.
     */
    private fun drainScidRegistrations() {
        while (true) {
            val (key, driver) = scidRegistrationQueue.poll() ?: break
            connectionsByDcid[key] = driver
        }
    }

    /**
     * Write a native size_t value into a buffer for quiche_header_info output params.
     */
    private fun initSizeTBuffer(
        buf: com.ditchoom.buffer.PlatformBuffer,
        value: Int,
    ) {
        val bb = (buf.unwrapFully() as com.ditchoom.buffer.BaseJvmBuffer).byteBuffer
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
        // onCleanup keeps peerSockAddr/localSockAddr strongly reachable for the driver's
        // lifetime — recvInfo holds only raw Long pointers into their PlatformBuffers,
        // so without this the buffers become GC-eligible immediately and DirectByteBuffer
        // Cleaner can free the memory mid-connection (see quiche/src/ffi.rs:2059 panic).
        // Self-reference for the onScidIssued callback: the driver doesn't exist yet when we build
        // the callback, so capture it via this holder, set right after construction. The callback
        // only fires on establishment (well after this), so the holder is always populated by then.
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
                onCleanup = {
                    peerSockAddr.free()
                    localSockAddr.free()
                },
                onScidIssued = { scid, len ->
                    // Snapshot the CID bytes now (scid is freed right after this returns) and hand
                    // the registration to the receive loop, which owns connectionsByDcid.
                    driverRef?.let { d ->
                        scidRegistrationQueue.add(ConnectionIdKey.from(scid, len) to d)
                        receiveSelector?.wakeup()
                    }
                },
            )
        driverRef = driver

        driver.start(scope)

        val scidKey = ConnectionIdKey.from(serverScid, QUIC_MAX_CONN_ID_LEN)
        // serverScid can be freed now — we have the key.
        // peerSockAddr/localSockAddr are kept alive by the driver's onCleanup closure
        // and released when the driver tears down.
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
 *
 * Holds a managed-heap snapshot of the CID bytes (typically ≤20 bytes
 * per RFC 9000 §5.1) so the key is stable across datagram buffer
 * recycling. Equality/hash reuse the buffer library's content-based
 * helpers, eliminating the per-datagram [ByteArray] that the pre-v2
 * implementation allocated.
 */
internal class ConnectionIdKey private constructor(
    private val snapshot: com.ditchoom.buffer.ReadBuffer,
) {
    override fun equals(other: Any?): Boolean = other is ConnectionIdKey && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = bufferHashCode(snapshot)

    companion object {
        fun from(
            buffer: com.ditchoom.buffer.PlatformBuffer,
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

    override suspend fun sendDatagram(buffer: com.ditchoom.buffer.ReadBuffer) = datagramAdapter.sendDatagram(buffer)

    override suspend fun receiveDatagram(): DatagramReceiveResult = datagramAdapter.receiveDatagram()

    override fun datagrams(): Flow<com.ditchoom.buffer.ReadBuffer> = datagramAdapter.datagrams()

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
