package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.bufferHashCode
import com.ditchoom.buffer.managed
import kotlinx.coroutines.channels.Channel

/**
 * Platform-agnostic connection-lifecycle bookkeeping shared by every quiche-backed QUIC server
 * (JVM/Android via `JvmQuicServer`, Linux via `LinuxQuicServer`, Apple via `AppleQuicServer`). Each
 * platform server keeps only its own I/O transport (NIO Selector / io_uring / NWConnection), its
 * sockaddr handling, and the wiring that constructs a driver — and delegates ALL of the following to
 * one instance of this registry:
 *
 *  - the [connectionsByDcid] *routing* table (DCID/SCID → driver),
 *  - the authoritative [liveDrivers] lifecycle ledger,
 *  - the per-source `recv_info` cache (passive-migration support) with its reference-counted eviction,
 *  - the driver-cleanup and spare-SCID registration queues, and
 *  - the load-bearing [reapAllDriversAndFreeRecvInfoCache] close sweep.
 *
 * This layer was independently reimplemented in all three server files and drifted — the intermittent
 * `recv_info` use-after-free (#179) had to be fixed three times, and its follow-up `toList()` race fix
 * ([LiveDriverLedger.snapshot]) landed on JVM only because the copies had diverged. Extracting it here
 * is fix-once: the invariants live in one place, and the concurrency primitives that genuinely differ
 * per platform (`java.util.concurrent` on the JVM vs. copy-on-write `kotlin.concurrent` atomics on
 * Kotlin/Native) sit behind the [LiveDriverLedger] / [RecvInfoRefCount] `expect`/`actual` seam.
 *
 * @param K the platform's recv_info cache source key — `java.net.InetSocketAddress` on the JVM, an
 *   allocation-free [PathKey] on Kotlin/Native. The registry never inspects it beyond map identity.
 *
 * Threading: [connectionsByDcid] and the recv_info cache are single-writer — mutated only on the
 * platform's receive-loop coroutine (route/lookup/drain), plus the one-shot [reapAllDriversAndFreeRecvInfoCache]
 * after that loop has been joined. [liveDrivers] and each [CachedRecvInfo.inFlight] are the only
 * cross-thread state (driver-cleanup coroutines / driver command execution) and use the seam atomics.
 */
internal class ServerConnectionRegistry<K>(
    private val api: QuicheApi,
) {
    /**
     * Routing table: every DCID and server-issued SCID currently mapping to a live driver. NOT a
     * lifecycle set — an entry is dropped the instant a driver stops routing new packets (see
     * [drainRoutingQueues] and the trySend-failure path via [deRouteDriver]) even though that
     * driver's run loop may still be draining buffered packets. Single-writer (receive loop).
     */
    private val connectionsByDcid = mutableMapOf<ConnectionIdKey, QuicheDriver>()

    /** Hand-off of newly accepted drivers from the receive loop to `connections()`'s consumer loop. */
    val acceptedDrivers = Channel<QuicheDriver>(Channel.UNLIMITED)

    /**
     * Authoritative ledger of every accepted driver whose run loop may still be alive — added the
     * instant a driver is started ([trackLiveDriver], before `driver.start`) and removed only once
     * its teardown has fully completed (via the driver's `onCleanup`, which runs after `run()`
     * returns → [untrackLiveDriver]).
     *
     * [connectionsByDcid] is a *routing* table, NOT a lifecycle set: a driver is dropped from it the
     * moment it stops routing new packets — while its run loop can still be draining already-buffered
     * `RecvPacket`s, each of which `connRecv`s a shared [peerRecvInfos] entry via
     * `QuicheCmd.RecvPacket.recvInfoOverride`. [reapAllDriversAndFreeRecvInfoCache] must therefore
     * destroy+join every driver in *this* set (a superset of the routing map), not just those still
     * routing, before it frees the recv_info cache — otherwise a lagging run loop `connRecv`s a
     * recv_info the sweep already freed. That was the intermittent recv_info use-after-free the JNI
     * SIGSEGV hunt chased (#179).
     */
    private val liveDrivers = LiveDriverLedger()

    /**
     * Queue for drivers whose [connectionsByDcid] entries need removing. Connection handlers enqueue
     * a driver after they close it ([enqueueCleanup]); the receive loop drains it ([drainRoutingQueues]),
     * keeping every routing-map mutation on the single receive-loop coroutine.
     */
    private val driverCleanupQueue = Channel<QuicheDriver>(Channel.UNLIMITED)

    /**
     * Queue of spare-SCID registrations from drivers' `onScidIssued` (fired on a driver coroutine when
     * a driver issues spare CIDs at establishment). The receive loop drains it into [connectionsByDcid]
     * ([drainRoutingQueues]) so a migrating peer's new DCID (a server-issued SCID) routes to the right
     * driver — without this, active migration fails path validation.
     */
    private val scidRegistrationQueue = Channel<Pair<ConnectionIdKey, QuicheDriver>>(Channel.UNLIMITED)

    /**
     * Passive-migration support: one server socket sees a client's source address change after the
     * client migrates (RFC 9000 §9). quiche needs the *actual* per-datagram source as `recv_info.from`
     * to recognise the new path, so the platform caches one recv_info per distinct source (its `to` is
     * the server's fixed local addr, owned platform-side).
     *
     * Bounded ([maxPeerRecvInfos]) so a peer spraying spoofed source addresses can't grow native memory
     * without bound. Access order is maintained manually (remove+reinsert on hit in [lookupRecvInfo])
     * because Kotlin/Native's `LinkedHashMap` has no `accessOrder` constructor. A cached recv_info
     * pointer is handed to a driver over an UNLIMITED command channel, so a lagging driver may still
     * hold it after the source goes idle; [CachedRecvInfo.inFlight] tracks outstanding references so
     * [evictIdlePeerRecvInfo] never frees one still in use. Single-writer (receive loop); [inFlight] is
     * the only field a driver coroutine mutates.
     */
    private val maxPeerRecvInfos = 256
    private val peerRecvInfos = LinkedHashMap<K, CachedRecvInfo>()

    // --- Routing table (receive-loop coroutine only) ---

    fun driverForDcid(key: ConnectionIdKey): QuicheDriver? = connectionsByDcid[key]

    fun routeDriver(
        key: ConnectionIdKey,
        driver: QuicheDriver,
    ) {
        connectionsByDcid[key] = driver
    }

    /** Remove ALL routing entries pointing at [driver] (a driver may hold several: SCID + DCIDs). */
    fun deRouteDriver(driver: QuicheDriver) {
        connectionsByDcid.keys.removeAll { connectionsByDcid[it] === driver }
    }

    fun enqueueCleanup(driver: QuicheDriver) {
        driverCleanupQueue.trySend(driver)
    }

    fun enqueueScidRegistration(
        key: ConnectionIdKey,
        driver: QuicheDriver,
    ) {
        scidRegistrationQueue.trySend(key to driver)
    }

    /**
     * Drain both routing queues into [connectionsByDcid]: cleanup removals first (so a stale
     * registration for an already-removed driver is harmless), then spare-SCID additions. Receive-loop
     * coroutine only.
     */
    fun drainRoutingQueues() {
        while (true) {
            val driver = driverCleanupQueue.tryReceive().getOrNull() ?: break
            connectionsByDcid.keys.removeAll { connectionsByDcid[it] === driver }
        }
        while (true) {
            val (key, driver) = scidRegistrationQueue.tryReceive().getOrNull() ?: break
            connectionsByDcid[key] = driver
        }
    }

    // --- Live-driver ledger ---

    /**
     * Ledger [driver] BEFORE its run loop starts so the close sweep can never observe a started-but-
     * untracked driver. Additions happen only on the receive-loop coroutine, so they cease once close()
     * has joined the receive loop — the set is then stable for [reapAllDriversAndFreeRecvInfoCache].
     */
    fun trackLiveDriver(driver: QuicheDriver) = liveDrivers.add(driver)

    /** Called from a driver's `onCleanup` (after `run()` fully returns, so it can no longer connRecv). */
    fun untrackLiveDriver(driver: QuicheDriver) = liveDrivers.remove(driver)

    // --- recv_info cache (receive-loop coroutine only, except CachedRecvInfo.inFlight) ---

    /** Cache hit → promote to most-recently-used and return; miss → null (caller builds via [putRecvInfo]). */
    fun lookupRecvInfo(key: K): CachedRecvInfo? {
        peerRecvInfos.remove(key)?.let {
            peerRecvInfos[key] = it // bump to most-recently-used
            return it
        }
        return null
    }

    /**
     * Insert a freshly-built recv_info for [key]. [info] is the native recv_info handle; [releaseSource]
     * frees the pinned per-source `from` sockaddr the platform allocated for it (a `NativeSockAddr` on
     * the JVM, a `PlatformBuffer` on Kotlin/Native). Evicts an idle over-cap entry afterwards.
     */
    fun putRecvInfo(
        key: K,
        info: QuicheRecvInfo,
        releaseSource: () -> Unit,
    ): CachedRecvInfo {
        val cached = CachedRecvInfo(info, releaseSource)
        peerRecvInfos[key] = cached
        evictIdlePeerRecvInfo()
        return cached
    }

    /**
     * Free the least-recently-used cached recv_info whose [CachedRecvInfo.inFlight] is zero once over
     * [maxPeerRecvInfos]. If every over-cap entry is still in flight (pathological), skip rather than
     * risk a use-after-free — we briefly exceed the cap and the next miss retries.
     */
    private fun evictIdlePeerRecvInfo() {
        if (peerRecvInfos.size <= maxPeerRecvInfos) return
        val iterator = peerRecvInfos.entries.iterator() // insertion order == access order (maintained above)
        while (iterator.hasNext()) {
            val cached = iterator.next().value
            if (cached.inFlight.get() == 0) {
                api.recvInfoFree(cached.info)
                cached.releaseSource()
                iterator.remove()
                return
            }
        }
    }

    // --- Teardown ---

    /**
     * The load-bearing close sweep. Call AFTER the platform has stopped and joined its receive loop
     * (so no new drivers are added and nothing else touches the routing map / cache) and BEFORE the
     * platform frees the shared `to` sockaddr or the native config.
     *
     * Destroys+joins EVERY driver in [liveDrivers] — a superset of [connectionsByDcid] — before freeing
     * the recv_info cache, so a driver dropped from the routing map but still draining buffered packets
     * can't `connRecv` a recv_info this sweep already freed (the #179 UAF). The [LiveDriverLedger.snapshot]
     * is race-safe against a driver concurrently removing itself via `onCleanup`. `destroy()` is
     * idempotent, so a driver that already finished is harmless here. The `check(inFlight == 0)` tripwire
     * is unreachable given the join — asserted so any future regression trips deterministically instead
     * of as an opaque native connRecv SIGSEGV.
     */
    suspend fun reapAllDriversAndFreeRecvInfoCache() {
        for (driver in liveDrivers.snapshot()) {
            driver.destroy()
        }
        liveDrivers.clear()
        connectionsByDcid.clear()
        for (cached in peerRecvInfos.values) {
            check(cached.inFlight.get() == 0) {
                "recv_info still in-flight at server close (inFlight=${cached.inFlight.get()})"
            }
            api.recvInfoFree(cached.info)
            cached.releaseSource()
        }
        peerRecvInfos.clear()
    }

    /** Close the hand-off + routing channels. Call after [reapAllDriversAndFreeRecvInfoCache]. */
    fun closeChannels() {
        acceptedDrivers.close()
        driverCleanupQueue.close()
        scidRegistrationQueue.close()
    }

    /**
     * TEST SEAM (do not call in production): drop every driver from the routing table without
     * destroying it, reproducing the state the cleanup-queue drain / trySend-failure removal create in
     * production — a *live* driver that is no longer routable. Lets a test assert the close sweep still
     * reaps such a driver (via [liveDrivers]) before it frees the recv_info cache. Safe only while the
     * receive loop is idle (the sole other writer of the map); the caller guarantees that.
     */
    internal fun deRouteAllDriversForTest() {
        connectionsByDcid.clear()
    }
}

/**
 * A cached per-source `recv_info` (passive-migration path). [info] is the native handle; [releaseSource]
 * frees the platform-pinned `from` sockaddr it points at. [inFlight] counts references handed to drivers
 * but not yet consumed, guarding [ServerConnectionRegistry] eviction/teardown against a use-after-free.
 */
internal class CachedRecvInfo(
    val info: QuicheRecvInfo,
    private val releaseSourceAddr: () -> Unit,
) {
    /** Packets handed to a driver but not yet consumed; guards against evict/free-while-in-use. */
    val inFlight = RecvInfoRefCount(0)

    /** Free the pinned per-source `from` sockaddr. Called by the registry after `recvInfoFree(info)`. */
    fun releaseSource() = releaseSourceAddr()
}

/**
 * Key for connection lookup by DCID/SCID.
 *
 * Holds a managed-heap snapshot of the CID bytes (typically ≤20 bytes per RFC 9000 §5.1) so the key is
 * stable across datagram buffer recycling. Equality/hash reuse the buffer library's content-based
 * helpers, eliminating the per-datagram `ByteArray` that the pre-v2 implementation allocated.
 */
internal class ConnectionIdKey private constructor(
    private val snapshot: com.ditchoom.buffer.ReadBuffer,
) {
    override fun equals(other: Any?): Boolean = other is ConnectionIdKey && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = bufferHashCode(snapshot)

    companion object {
        fun from(
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
