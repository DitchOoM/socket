@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.udp.SocketAddressCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * An accepted server connection handed from the receive loop to `connections()`: the [driver] plus the
 * [remoteAddress] of the peer that opened it. The peer is only known on the receive loop, so it rides
 * alongside the driver here to become the handler connection's `remoteAddress` (the datagram peer).
 */
internal class AcceptedConnection(
    val driver: QuicheDriver,
    val remoteAddress: SocketAddress,
)

/**
 * The one QUIC server implementation, shared by every platform (JVM/Android via `buildJvmQuicServer`,
 * Linux via `buildLinuxQuicServer`, Apple via `buildAppleQuicServer`). It binds over a `:socket-udp`
 * [DatagramChannel]: a dedicated reader coroutine confines the channel's `receive()` (the buffer-flow
 * single-consumer contract) and feeds a central [receiveLoop] that parses each datagram's DCID to route
 * it to its [QuicheDriver] — or [acceptNewConnection]s a new one. All connection-lifecycle bookkeeping
 * (routing table, live-driver ledger, per-source recv_info cache, close sweep — the #179 recv_info-UAF
 * invariants) lives in the shared [ServerConnectionRegistry].
 *
 * Everything here is platform-independent; the genuine per-platform differences are hidden behind three
 * seams so this class can be common: [serverReceiveDispatcher] (IO vs Default), [writeNativeSizeT] /
 * [readNativeSizeT] (direct `ByteBuffer` vs cinterop), and [PeerPathTable] (concurrent vs copy-on-write
 * map). The sockaddr [codec] and [QuicheApi] are ordinary constructor arguments the build functions fill
 * in per platform. This replaced three near-identical `JvmQuicServer` / `LinuxQuicServer` /
 * `AppleQuicServer` copies of the receive loop — the routing bookkeeping is now fix-once.
 */
internal class SharedQuicheServer(
    private val api: QuicheApi,
    private val config: QuicheConfig,
    private val channel: DatagramChannel,
    private val localAddress: SocketAddress,
    private val codec: SocketAddressCodec,
    private val bufferFactory: BufferFactory,
    parentScope: CoroutineScope,
    private val keepAliveInterval: Duration? = null,
    // Per-call lifecycle teardown wired by the build function (cancel the parent scope). Invoked last by
    // close(); null for any direct-construction test that owns the scope externally.
    private val onClose: (() -> Unit)? = null,
    // Determinism seams forwarded to every accepted connection's driver (RFC_DETERMINISTIC_SIMULATION.md §3.1).
    private val tuning: QuicheDriverTuning = QuicheDriverTuning(),
    /**
     * Recv buffer pool — each datagram's ownership transfers to the connection's driver and every
     * free-path recycles it back. Hoisted to a ctor param (default: a fresh pool) so the build function
     * injects the SAME instance as the shared [channel]'s `bufferFactory`: the channel allocates each
     * datagram from it and the receive loop routes it with no copy (B2 elimination). Built from a **leaf**
     * [bufferFactory] — never an already-pooled factory (the `80575c1` double-wrap regression).
     */
    private val recvBufPool: BufferPool = QuicheDriver.newRecvBufPool(bufferFactory),
) : QuicServer {
    override val port: Int get() = localAddress.port

    // Child scope of the per-call parent — cancelling it takes down every handler coroutine spawned
    // via connections() plus the receive loop.
    private val serverJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + serverJob)

    /** Connection-lifecycle bookkeeping shared with the platform build functions (see [ServerConnectionRegistry]). */
    private val registry = ServerConnectionRegistry<SocketAddress>(api)

    /**
     * PathKey→peer resolution for the egress channels (sendInfo.to → real send target, no address
     * reconstruction — RFC §4). Written only by the receive loop (cache miss / evict) plus the close
     * sweep after the loop has joined; read by driver egress coroutines. Entries are removed when their
     * recv_info cache entry is freed. The concurrency primitive is a per-platform seam ([PeerPathTable]).
     */
    private val peers = PeerPathTable()

    /** Prompt-wake for the receive loop on a routing-table change (connection close / spare SCID). */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** The shared recv_info `to` sockaddr (the server's fixed local addr), encoded lazily; freed in close(). */
    private var serverLocalSockAddr: EncodedSockAddr? = null

    @Volatile
    private var closed = false

    private val receiveJob = scope.launch(serverReceiveDispatcher) { receiveLoop() }

    override suspend fun connections(handler: suspend QuicScope.() -> Unit) =
        // Bind handler lifetime to the caller's coroutine: cancelling the coroutine that called
        // connections() must cancel each in-flight handler. coroutineScope { … } suspends until every
        // launched child returns, making the lifetime explicit (structured concurrency).
        coroutineScope {
            for (accepted in registry.acceptedDrivers) {
                val driver = accepted.driver
                launch(serverReceiveDispatcher) {
                    val connJob = SupervisorJob(coroutineContext[Job])
                    val connScope = CoroutineScope(coroutineContext + connJob)
                    val conn = DriverQuicConnection(driver, bufferFactory, accepted.remoteAddress, connScope)
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
     * — drops every driver from the routing table without destroying it, deterministically reproducing the
     * *live-but-unroutable* state the trySend-failure removal / cleanup-queue drain create in production.
     * Lets a test assert [close] still reaps such a driver before it frees the recv_info cache (the #179
     * recv_info-UAF invariant). Safe only while the receive loop is idle.
     */
    internal fun deRouteAllDriversForTest() {
        registry.deRouteAllDriversForTest()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        // Closing the :socket-udp channel makes any in-flight receive return promptly, so the reader's
        // receive() returns Closed, the loop exits, and receiveJob completes.
        channel.close()
        receiveJob.join()
        // receiveJob.join() guarantees no new drivers are added/routed and nothing else touches the
        // registry. Destroy+join EVERY live driver (a superset of the routing table) before freeing the
        // per-source recv_info cache — the #179 UAF invariant.
        registry.reapAllDriversAndFreeRecvInfoCache()
        serverLocalSockAddr?.free()
        serverLocalSockAddr = null
        // Drivers have drained — safe to free cached recv buffers. A release after this point would
        // silently repopulate the pool (benign), which is why it follows the destroy loop.
        recvBufPool.clear()
        api.configFree(config)
        registry.closeChannels()
        serverJob.cancel()
        // Finally, cancel the per-call parent scope (the build function wires this). serverJob is a child
        // of it, so this is the strict superset.
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
                launch(serverReceiveDispatcher) {
                    try {
                        while (true) {
                            val r =
                                try {
                                    channel.receive()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // Transient receive error — retry, unless the server is closing, in
                                    // which case fall through to shut the reader down.
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

                    writeNativeSizeT(scidLenBuf, QUIC_MAX_CONN_ID_LEN)
                    writeNativeSizeT(dcidLenBuf, QUIC_MAX_CONN_ID_LEN)
                    writeNativeSizeT(tokenLenBuf, MAX_TOKEN_LEN)

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
                                peers.put(fromKey, peer)
                                registry.putRecvInfo(peer, info) {
                                    from.free()
                                    peers.remove(fromKey)
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
                            // Remove ALL entries for this dead driver, not just the one we hit.
                            registry.deRouteDriver(existingDriver)
                        }
                    } else {
                        // Accept new connection — recvBuf ownership transfers inside.
                        val accepted = acceptNewConnection(recvBuf, received, peer)
                        if (accepted != null) {
                            val (driver, serverScidKey) = accepted
                            registry.routeDriver(serverScidKey, driver)
                            registry.routeDriver(dcidKey, driver)
                            registry.acceptedDrivers.trySend(AcceptedConnection(driver, peer))
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
        // quiche echoes as sendInfo.to for the un-migrated path); peers resolves a migrated source.
        val udpChannel =
            ServerConnectionUdpChannel(
                channel = channel,
                fixedPeer = peer,
                fixedPeerKey = api.decodePathKey(peerSockAddr.address),
                peerFor = peers::get,
            )
        // Self-reference for onScidIssued: the driver doesn't exist when we build the callback, so
        // capture it via this holder, set right after construction.
        var driverRef: QuicheDriver? = null
        val driver =
            QuicheDriver(
                rawApi = api,
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
        private const val MAX_TOKEN_LEN = 256
    }
}
