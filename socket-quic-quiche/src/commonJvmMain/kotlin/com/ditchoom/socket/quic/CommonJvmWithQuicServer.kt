@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.buffer.use
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * Build + bind a JVM/Android quiche-backed [JvmQuicServer], returning it ready to accept. The
 * returned server owns its full teardown via [JvmQuicServer.close] (UDP socket, drivers, handler
 * coroutines, config, and — via the `onClose` lambda wired below — the per-call parent scope). The
 * caller (the [withQuicServer] wrapper) only runs the block and calls `close()`.
 *
 * Lives in `commonJvmMain` so both `jvmMain` and `androidMain` reach it via [QuicheEngine.bind].
 */
internal suspend fun buildJvmQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    // Determinism seams (RFC_DETERMINISTIC_SIMULATION.md §3.1) — production defaults are
    // byte-identical to the pre-seam behaviour; the sim harness injects its own.
    tuning: QuicheDriverTuning = QuicheDriverTuning(),
    // Injectable backend — defaults to the process-wide native binding. A test passes a delegating
    // spy (e.g. to gate connRecv) exactly as the client's commonJvmWithQuicConnection already allows.
    api: QuicheApi = loadQuicheApi(),
): JvmQuicServer {
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.IO)
    var bound = false

    // QUIC I/O needs native-memory buffers (quiche FFI); see BufferFactory.network(). This is not a
    // caller-configurable knob: the quiche JNI/FFM binding dereferences buffer addresses everywhere
    // (cert/key load, header_info out-params, recv buffers, sockaddrs), so a managed/heap factory
    // can't back a QUIC server on ANY platform — including the JVM. See requireNativeMemory().
    val bufferFactory = BufferFactory.network()

    val config = api.configNew(QUICHE_PROTOCOL_VERSION)
    try {
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

        // Bind the shared unconnected server socket via :socket-udp (Phase 6 adapter-first cutover), sized
        // to QUIC datagrams (not the 64 KB UDP ceiling). One channel serves every accepted connection;
        // per-connection egress is a thin ServerConnectionUdpChannel over it.
        // One recv pool for the whole server, injected as the shared channel's bufferFactory so each
        // datagram is allocated straight from it — the receive loop then routes it with no copy.
        val recvBufPool = QuicheDriver.newRecvBufPool(bufferFactory)
        val channel =
            UdpSocket.bind(host, port, receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE, bufferFactory = recvBufPool)
        val localAddress = channel.localAddress ?: error("bound server channel has no local address")

        val server =
            JvmQuicServer(
                api,
                config,
                channel,
                localAddress,
                bufferFactory,
                parentScope,
                quicOptions.keepAliveInterval,
                // server.close() frees config + drivers; the per-call parent scope is the server's
                // to cancel last, so the withQuicServer wrapper stays a plain block + close().
                onClose = { parentScope.cancel() },
                tuning = tuning,
                recvBufPool = recvBufPool,
            )
        bound = true
        return server
    } finally {
        // Bind failed before JvmQuicServer took ownership of config + scope — release here.
        if (!bound) {
            api.configFree(config)
            parentScope.cancel()
        }
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
    private val localAddress: SocketAddress,
    private val bufferFactory: BufferFactory,
    parentScope: CoroutineScope,
    private val keepAliveInterval: Duration? = null,
    // Per-call lifecycle teardown wired by buildJvmQuicServer (cancel the parent scope). Null for
    // any direct-construction test that owns the scope externally. Invoked last by close().
    private val onClose: (() -> Unit)? = null,
    // Determinism seams forwarded to every accepted connection's driver (RFC_DETERMINISTIC_SIMULATION.md §3.1).
    private val tuning: QuicheDriverTuning = QuicheDriverTuning(),
    /**
     * Recv buffer pool — one MAX_DATAGRAM_SIZE (1350-byte) buffer per incoming UDP datagram, whose
     * ownership transfers to the routed driver and returns here on `freeNativeMemory()` after
     * `quiche_conn_recv`. Hoisted to a ctor param (default: a fresh pool) so [buildJvmQuicServer] can
     * inject the SAME instance as the shared [channel]'s `bufferFactory`: the channel then allocates each
     * datagram straight from this pool and the receive loop routes it with no copy (B2 elimination).
     *
     * MultiThreaded mode: the reader coroutine acquires; drivers release (per-connection coroutine).
     * Ownership invariant: built from a **leaf** [bufferFactory] — never an already-pooled factory (the
     * `80575c1` double-wrap regression).
     */
    private val recvBufPool: BufferPool = QuicheDriver.newRecvBufPool(bufferFactory),
) : QuicServer {
    override val port: Int get() = localAddress.port

    /**
     * The one differential-tested sockaddr codec (Phase 6 SPI) — encodes peer/local [SocketAddress]es
     * into the C sockaddr bytes quiche's `accept`/`recv_info` FFI needs, replacing SockAddrUtil.
     */
    private val codec = SocketAddressCodec(hostOsSockAddrLayout())

    /**
     * Shared PathKey→peer map resolving `sendInfo.to` back to a real send target on the egress channels,
     * without reconstructing an address from the opaque [PathKey] (RFC §4). Populated by the receive loop
     * from each passive-migration source's `Datagram.peer`; each entry is removed when its recv_info cache
     * entry is evicted/freed, so it stays bounded to the live recv_info cache. Cross-thread: written on
     * the receive loop, read by driver egress coroutines — hence a concurrent map.
     */
    private val peersByPathKey = ConcurrentHashMap<PathKey, SocketAddress>()

    /**
     * Prompt-wake signal for the receive loop, replacing the old shared `Selector.wakeup()`: a
     * connection handler that closes a driver (or a driver issuing spare SCIDs) enqueues a routing-table
     * change and pokes this so the loop drains it *now* instead of on the next inbound datagram. Conflated
     * — many pokes collapse to one drain, which drains the whole queue. Reactive (no polling): with the
     * select over a dedicated reader, only a real datagram or a real poke wakes the loop.
     */
    private val wakeups = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    // Child scope of the per-call parent — cancelling it takes down every handler
    // coroutine spawned via connections() plus the receive loop. Without this,
    // handlers launched on the parent scope leak past server.close() and pile
    // up on Dispatchers.IO across successive tests.
    private val serverJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + serverJob)

    /**
     * All connection-lifecycle bookkeeping (routing table, live-driver ledger, per-source recv_info
     * cache, cleanup/SCID queues, and the load-bearing close sweep). Only this server's NIO Selector
     * transport and its `InetSocketAddress`→sockaddr handling stay here; see [ServerConnectionRegistry].
     */
    private val registry = ServerConnectionRegistry<SocketAddress>(api)

    /**
     * The shared recv_info `to` sockaddr: the server's fixed local address, reused by every cached
     * per-source recv_info the [registry] holds (its `from` is the datagram's actual source, needed
     * for passive migration per RFC 9000 §9). Built lazily on the receive loop at the first cache
     * miss; freed in [close] after the registry sweep. The per-source `from` addrs are owned by the
     * registry's cache entries.
     */
    private var serverLocalSockAddr: EncodedSockAddr? = null

    @Volatile private var closed = false

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
            for (driver in registry.acceptedDrivers) {
                launch(Dispatchers.IO) {
                    val connJob = SupervisorJob(coroutineContext[Job])
                    val connScope = CoroutineScope(coroutineContext + connJob)
                    val conn = DriverQuicConnection(driver, bufferFactory, connScope)
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

    /**
     * TEST SEAM (do not call in production): delegates to [ServerConnectionRegistry.deRouteAllDriversForTest]
     * — drops every driver from the routing table without destroying it, deterministically reproducing
     * the *live-but-unroutable* state the trySend-failure removal / cleanup-queue drain create in
     * production. Lets a test assert [close] still reaps such a driver before it frees the recv_info
     * cache (the invariant behind the #179 recv_info UAF fix). Safe only while the receive loop is idle.
     */
    internal fun deRouteAllDriversForTest() {
        registry.deRouteAllDriversForTest()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        // Closing the :socket-udp channel wakes the receive loop's internal select and makes its
        // dc.receive() return Closed, so the loop exits and receiveJob completes.
        channel.close()
        receiveJob.join()
        // receiveJob.join() above guarantees no new drivers are added or routed and nothing else
        // touches the registry's maps/cache. Destroy+join EVERY live driver (a superset of the
        // routing table) before freeing the per-source recv_info cache — the #179 UAF invariant.
        registry.reapAllDriversAndFreeRecvInfoCache()
        // The shared `to` sockaddr every cached recv_info pointed at — freed after the sweep freed
        // the recv_info structs and their per-source `from` addrs.
        serverLocalSockAddr?.free()
        serverLocalSockAddr = null
        // Drivers have drained — safe to free cached recv buffers. Any release
        // after this point would silently repopulate the pool (benign, but
        // buffers would leak until server GC), which is why this follows the
        // destroy loop.
        recvBufPool.clear()
        api.configFree(config)
        registry.closeChannels()
        // Cancel handler coroutines spawned via connections() — they're
        // children of serverJob. Non-blocking: the guarantee we need is
        // asserted by JvmQuicServerLifecycleTests (no coroutines outlive
        // server.close()).
        serverJob.cancel()
        // Finally, cancel the per-call parent scope (buildJvmQuicServer wires this); previously the
        // withQuicServer wrapper did it. serverJob is a child of it, so this is the strict superset.
        onClose?.invoke()
    }

    /**
     * Async receive loop over the shared `:socket-udp` channel. A dedicated [reader] confines the
     * channel's `receive()` to one coroutine (per the buffer-flow single-consumer contract) and hands
     * each datagram to this loop over a rendezvous channel; the loop [select]s that against [wakeups] so
     * a routing-table change (connection close / spare SCID) drains promptly without an external selector
     * wake. Each datagram carries its per-packet source ([com.ditchoom.buffer.flow.Datagram.peer]) and its
     * payload is already a [recvBufPool] buffer (the channel's `bufferFactory`), so ownership transfers to
     * the routed driver with no copy (freed back to the pool after `quiche_conn_recv`); we then parse its
     * header to route or accept. [close]'s `channel.close()` makes the reader's `receive()` return Closed,
     * which unwinds this loop.
     */
    private suspend fun receiveLoop() =
        kotlinx.coroutines.coroutineScope {
            // Header parsing output buffers (reused across iterations — these are scratch space)
            val versionBuf = bufferFactory.allocate(4)
            val typeBuf = bufferFactory.allocate(1)
            val scidBuf = bufferFactory.allocate(QUIC_MAX_CONN_ID_LEN)
            val scidLenBuf = bufferFactory.allocate(8)
            val dcidBuf = bufferFactory.allocate(QUIC_MAX_CONN_ID_LEN)
            val dcidLenBuf = bufferFactory.allocate(8)
            val tokenBuf = bufferFactory.allocate(MAX_TOKEN_LEN)
            val tokenLenBuf = bufferFactory.allocate(8)

            val inbound = kotlinx.coroutines.channels.Channel<DatagramReadResult>(kotlinx.coroutines.channels.Channel.RENDEZVOUS)
            val reader =
                launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val r =
                                try {
                                    channel.receive()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // Transient receive error — retry (matches the old loop), unless the
                                    // server is closing, in which case fall through to shut the reader down.
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
                        kotlinx.coroutines.selects.select<Any?> {
                            inbound.onReceiveCatching { res -> res.getOrNull() ?: readerDone }
                            wakeups.onReceive { wake }
                        }
                    // Register spare SCIDs / drop closed drivers BEFORE routing anything — matches the old
                    // "drain right after the select wakes" order. Receive-loop coroutine is the sole writer.
                    registry.drainRoutingQueues()

                    val datagram =
                        when (item) {
                            wake -> continue@loop // woke only to drain the routing queues
                            readerDone -> break@loop // reader saw Closed / error → shut down
                            is DatagramReadResult.Closed -> break@loop
                            is DatagramReadResult.Received -> item.datagram
                            else -> continue@loop
                        }
                    if (closed) {
                        datagram.payload.freeNativeMemory()
                        break@loop
                    }

                    val peer = datagram.peer
                    // The channel allocated this payload straight from recvBufPool (its bufferFactory), so
                    // it IS the pooled recv buffer — route it directly with no copy. Ownership transfers to
                    // the driver, which frees it back to the pool after quiche_conn_recv.
                    val recvBuf = datagram.payload
                    val received = recvBuf.remaining()
                    if (received <= 0) {
                        recvBuf.freeNativeMemory()
                        continue@loop
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
                        continue@loop
                    }

                    val dcidLen = readNativeSizeT(dcidLenBuf)
                    val dcidKey = ConnectionIdKey.from(dcidBuf, dcidLen)

                    val existingDriver = registry.driverForDcid(dcidKey)
                    if (existingDriver != null) {
                        // The per-source recv_info lets quiche see the real datagram origin so a migrated
                        // client's new source is recognised as a new path (passive migration). Hold an
                        // in-flight ref so the cache can't evict+free it while the driver has it queued.
                        val cached =
                            registry.lookupRecvInfo(peer) ?: run {
                                // Cache miss: build a recv_info from = the datagram's source, to = the
                                // server's fixed local addr (encoded once, kept in serverLocalSockAddr).
                                val local =
                                    serverLocalSockAddr ?: codec.encodeToNative(localAddress, bufferFactory).also {
                                        serverLocalSockAddr = it
                                    }
                                val from = codec.encodeToNative(peer, bufferFactory)
                                val info = api.recvInfoNew(from.address, from.length, local.address, local.length)
                                // Record this source's PathKey→peer so a reply quiche routes here
                                // (sendInfo.to) resolves to the real send target; removed on cache free.
                                val fromKey = api.decodePathKey(from.address)
                                peersByPathKey[fromKey] = peer
                                registry.putRecvInfo(peer, info) {
                                    from.free()
                                    peersByPathKey.remove(fromKey)
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
                            // Not delivered → onRecvInfoConsumed won't fire; release the ref here.
                            cached.inFlight.decrementAndGet()
                            recvBuf.freeNativeMemory()
                            // Remove ALL entries for this dead driver, not just the one we hit
                            registry.deRouteDriver(existingDriver)
                        }
                    } else {
                        // Accept new connection — recvBuf ownership transfers inside
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

        // Per-connection egress over the shared server socket. fixedPeerKey is this peer's PathKey (what
        // quiche echoes as sendInfo.to for the un-migrated path); peersByPathKey resolves a migrated
        // source's key. See ServerConnectionUdpChannel.
        val udpChannel =
            ServerConnectionUdpChannel(
                channel = channel,
                fixedPeer = peer,
                fixedPeerKey = api.decodePathKey(peerSockAddr.address),
                peerFor = peersByPathKey::get,
            )
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
                keepAliveInterval = keepAliveInterval,
                clock = tuning.clock,
                driverContext = tuning.driverContext,
                random = tuning.random,
                recorder = tuning.recorderFactory(),
                onCleanup = {
                    // Runs after run() has fully returned, so the driver can no longer connRecv —
                    // drop it from the lifecycle ledger (bounds the set for long-lived servers).
                    driverRef?.let { registry.untrackLiveDriver(it) }
                    peerSockAddr.free()
                    localSockAddr.free()
                },
                onScidIssued = { scid, len ->
                    // Snapshot the CID bytes now (scid is freed right after this returns) and hand
                    // the registration to the receive loop, which owns the routing table; poke it to
                    // drain promptly so a migrating peer's new DCID routes before its first packet.
                    driverRef?.let { d ->
                        registry.enqueueScidRegistration(ConnectionIdKey.from(scid, len), d)
                        wakeups.trySend(Unit)
                    }
                },
            )
        driverRef = driver

        // Ledger the driver BEFORE starting its run loop so close() can never observe a started-but-
        // untracked driver (additions happen only here, on the receive-loop thread, so they cease
        // once close() has joined receiveJob — the set is then stable for close()'s destroy sweep).
        registry.trackLiveDriver(driver)
        driver.start(scope)

        val scidKey = ConnectionIdKey.from(serverScid, QUIC_MAX_CONN_ID_LEN)
        // serverScid can be freed now — we have the key.
        // peerSockAddr/localSockAddr are kept alive by the driver's onCleanup closure
        // and released when the driver tears down.
        serverScid.freeNativeMemory()
        return driver to scidKey
    }

    companion object {
        private const val MAX_TOKEN_LEN = 256
    }
}

/**
 * QUIC connection backed by a [QuicheDriver].
 * Used by both client and server — the driver handles the differences.
 */
internal class DriverQuicConnection(
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
