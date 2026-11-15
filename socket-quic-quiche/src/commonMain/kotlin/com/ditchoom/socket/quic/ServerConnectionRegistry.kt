package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.bufferHashCode
import com.ditchoom.buffer.managed
import kotlinx.coroutines.channels.Channel
import kotlin.time.TimeMark

/**
 * Platform-agnostic connection-lifecycle bookkeeping shared by every quiche-backed QUIC server
 * (JVM/Android via `JvmQuicServer`, Linux via `LinuxQuicServer`, Apple via `AppleQuicServer`). Each
 * platform server keeps only its own I/O transport (NIO Selector / io_uring / NWConnection), its
 * sockaddr handling, and the wiring that constructs a driver — and delegates ALL of the following to
 * one instance of this registry:
 *
 *  - the [connectionsByDcid] *routing* table (DCID/SCID → driver),
 *  - the authoritative [liveDrivers] lifecycle ledger,
 *  - each connection's [ConnectionPaths]: its per-path `recv_info`s (passive-migration support) and
 *    their reference-counted release,
 *  - the driver-cleanup and source-CID projection queues, and
 *  - the load-bearing [reapAllDriversAndFreeRecvInfoCache] close sweep.
 *
 * One implementation for every platform, so the invariants live in one place; the concurrency
 * primitives that genuinely differ per platform (`java.util.concurrent` on the JVM vs. copy-on-write
 * `kotlin.concurrent` atomics on Kotlin/Native) sit behind the [LiveDriverLedger] /
 * [RecvInfoRefCount] `expect`/`actual` seam.
 *
 * @param K what identifies one path of a connection to the server (see [ServerPathPair]). The registry
 *   never inspects it beyond map identity.
 *
 * Threading: [connectionsByDcid] and every [ConnectionPaths] are single-writer — mutated only on the
 * platform's receive-loop coroutine (route/lookup/drain), plus the one-shot [reapAllDriversAndFreeRecvInfoCache]
 * after that loop has been joined. [liveDrivers] and each [CachedRecvInfo.inFlight] are the only
 * cross-thread state (driver-cleanup coroutines / driver command execution) and use the seam atomics.
 */
internal class ServerConnectionRegistry<K>(
    private val api: QuicheApi,
    /** The clock a closed connection's draining period is measured on — the drivers' own. */
    private val clock: DriverClock = RealDriverClock,
    /**
     * Routing table: every DCID and server-issued SCID currently mapping to a live driver. NOT a
     * lifecycle set — an entry is dropped the instant a driver stops routing new packets (see
     * [drainRoutingQueues] and the trySend-failure path via [deRouteDriver]) even though that
     * driver's run loop may still be draining buffered packets. Single-writer (receive loop).
     */
    private val connectionsByDcid: MutableMap<ConnectionIdKey, QuicheDriver> = mutableMapOf(),
) {

    /**
     * Hand-off of newly accepted connections from the receive loop to `connections()`'s consumer loop.
     * Carries the accepted driver together with its remote peer address so the handler's
     * [DriverQuicConnection] can expose `remoteAddress` (the datagram-channel peer) — the receive loop
     * is the only place the peer [com.ditchoom.buffer.flow.SocketAddress] is known.
     */
    val acceptedDrivers = Channel<AcceptedConnection>(Channel.UNLIMITED)

    /**
     * Authoritative ledger of every accepted driver whose run loop may still be alive — added the
     * instant a driver is started ([trackLiveDriver], before `driver.start`) and removed only once
     * its teardown has fully completed (via the driver's `onCleanup`, which runs after `run()`
     * returns → [untrackLiveDriver]).
     *
     * [connectionsByDcid] is a *routing* table, NOT a lifecycle set: a driver is dropped from it the
     * moment it stops routing new packets — while its run loop can still be draining already-buffered
     * `RecvPacket`s, each of which `connRecv`s one of its [ConnectionPaths]' recv_infos
     * ([PacketSource.FromServerSocket]). [reapAllDriversAndFreeRecvInfoCache] must therefore
     * destroy+join every driver in *this* set (a superset of the routing map), not just those still
     * routing, before it frees the recv_infos — otherwise a lagging run loop `connRecv`s a
     * recv_info the sweep already freed: an intermittent native use-after-free.
     */
    private val liveDrivers = LiveDriverLedger()

    /**
     * Queue for drivers whose [connectionsByDcid] entries need removing. Connection handlers enqueue
     * a driver after they close it ([enqueueCleanup]); the receive loop drains it ([drainRoutingQueues]),
     * keeping every routing-map mutation on the single receive-loop coroutine.
     */
    private val driverCleanupQueue = Channel<QuicheDriver>(Channel.UNLIMITED)

    /**
     * Source-CID **projections** from drivers ([QuicheDriver.onSourceIds], fired on a driver
     * coroutine whenever quiche's set may have moved). Each element is a driver together with
     * everything quiche currently recognises for it; the receive loop applies it to
     * [connectionsByDcid] in [drainRoutingQueues], because the map belongs to that coroutine.
     *
     * **One queue of whole sets, not separate registration and retirement events.** Two event streams
     * would have a relative order to reason about (additions before removals, so a re-registered id is
     * not unrouted by an older connection's retirement), and either could leave the map permanently
     * wrong if dropped. A whole set has no ordering question and no accumulation: applying the same
     * projection twice changes nothing, and a projection that never lands is superseded by the next
     * one.
     */
    private val routeProjectionQueue = Channel<RouteProjection>(Channel.UNLIMITED)

    /**
     * What the last applied projection routed to each driver — the baseline the next one is diffed
     * against, and the reason a projection removes only ids **it** placed.
     *
     * A server routes more than its own source CIDs: the client's original destination CID, chosen by
     * the client during the handshake, is registered at accept ([routeDriver]) and is not, and never
     * was, one of quiche's `source_ids` (`Connection::source_ids()` is `ids.scids_iter()`). quiche
     * therefore has no opinion about it, and a projection that removed every key it did not mention
     * would unroute the connection mid-handshake, dropping the client's Initial retransmissions.
     */
    private val projectedRoutes = mutableMapOf<QuicheDriver, Set<ConnectionIdKey>>()

    /**
     * The ids [routeDriver] registered for each driver outside any projection. With [projectedRoutes]
     * it names every id a driver can hold a route for, so retiring one visits its own ids and not the
     * table. Receive-loop only.
     */
    private val registeredRoutes = mutableMapOf<QuicheDriver, MutableList<ConnectionIdKey>>()

    /**
     * The [ConnectionPaths] of every connection that is still routed or was tracked and not yet
     * retired. Keyed by connection, so the paths one peer is seen on are bounded for that peer alone:
     * no number of other connections, and no spray of source addresses at one of them, costs a
     * different connection its own. Receive-loop only.
     */
    private val pathsByDriver = mutableMapOf<QuicheDriver, ConnectionPaths<K>>()

    /**
     * The paths of retired connections that a queued packet still referenced when their connection
     * was retired; each is freed by a later [drainRoutingQueues] once its driver has let go.
     * Receive-loop only.
     */
    private val retiredPaths = mutableListOf<ConnectionPaths<K>>()

    /**
     * The ids of connections closed within their draining period (RFC 9000 §10.2), each with the moment
     * it ends. A closing or draining endpoint keeps enough state to recognise packets for a connection it
     * has just closed, and on every close some arrive: the peer's packets already in flight, its own
     * CONNECTION_CLOSE crossing ours. Recognised, they are an expected drop rather than an unknown id.
     * Receive-loop only; an entry is dropped when its [Retirement] expires, or by the first lookup after
     * its own period ends.
     */
    private val closedConnections = mutableMapOf<ConnectionIdKey, TimeMark>()

    /**
     * The drivers [retire] has removed, each until its draining period ends. Retirement is a terminal
     * fact, so it is recorded as one rather than left to the order the queues happen to drain in: a
     * driver's last turn can enqueue a projection *after* the receive loop has applied its cleanup, and
     * a projection applied on its own finds no baseline and would route the dead driver again and erase
     * its [closedConnections] entries. A projection for a retired driver is dropped. Entries live at
     * least as long as the ids they retired: the driver's loop ends within a turn of its cleanup, far
     * inside one draining period. Receive-loop only.
     */
    private val retiredDrivers = mutableMapOf<QuicheDriver, TimeMark>()

    /**
     * Every retirement whose [closedConnections] and [retiredDrivers] entries are still held, oldest
     * first. [drainRoutingQueues] forgets from the front while the front has expired, so a drain reads
     * the clock once however many connections closed recently. Draining periods differ per connection,
     * so an entry can wait behind an older, longer one; it is forgotten late, never early, and
     * [routeFor] judges each id by its own period. Receive-loop only.
     */
    private val retirements = ArrayDeque<Retirement>()

    // --- Routing table (receive-loop coroutine only) ---

    fun driverForDcid(key: ConnectionIdKey): QuicheDriver? = connectionsByDcid[key]

    /** Where a datagram addressed to [key] belongs. See [DcidRoute]. */
    fun routeFor(key: ConnectionIdKey): DcidRoute {
        connectionsByDcid[key]?.let { return DcidRoute.Live(it) }
        val until = closedConnections[key] ?: return DcidRoute.Unknown
        if (until.hasPassedNow()) {
            closedConnections.remove(key)
            return DcidRoute.Unknown
        }
        return DcidRoute.RecentlyClosed
    }

    /** Move every id routed to [driver] into [closedConnections] for the driver's draining period. */
    private fun retire(driver: QuicheDriver) {
        val until = clock.markNow() + driver.drainingPeriod
        val held = registeredRoutes.remove(driver).orEmpty() + projectedRoutes.remove(driver).orEmpty()
        // Identity-checked: an id this driver once held may route another connection by now.
        val closed = held.filter { connectionsByDcid[it] === driver }
        for (key in closed) {
            connectionsByDcid.remove(key)
            closedConnections[key] = until
        }
        retiredDrivers[driver] = until
        retirements.addLast(Retirement(driver, closed, until))
        pathsByDriver.remove(driver)?.let { if (!it.freeIdle()) retiredPaths += it }
    }

    fun routeDriver(
        key: ConnectionIdKey,
        driver: QuicheDriver,
    ) {
        connectionsByDcid[key] = driver
        closedConnections.remove(key)
        registeredRoutes.getOrPut(driver) { mutableListOf() } += key
    }

    /**
     * Stop routing to [driver], which has closed: every id it held (SCID + DCIDs) moves to
     * [closedConnections] for its draining period. The projection baseline goes with them: it describes
     * what is in the map, and claiming routes the map no longer holds would make the next diff a no-op.
     */
    fun deRouteDriver(driver: QuicheDriver) = retire(driver)

    fun enqueueCleanup(driver: QuicheDriver) {
        driverCleanupQueue.trySend(driver)
    }

    /**
     * Publish [driver]'s current source-CID set — everything quiche recognises for it, right now.
     *
     * Called from a driver coroutine; the receive loop applies it. Carries the driver, not just the
     * ids, for the same reason the retirement queue it replaces did: a CID is unique per connection
     * while it is live, but the same bytes may be re-issued by a *later* connection, and touching the
     * map by key alone would let one connection's bookkeeping unroute another's.
     */
    fun enqueueRouteProjection(
        driver: QuicheDriver,
        sourceIds: Set<ConnectionIdKey>,
    ) {
        routeProjectionQueue.trySend(RouteProjection(driver, sourceIds))
    }

    /**
     * Apply the routing queues to [connectionsByDcid]: projections first, cleanup removals last.
     * Receive-loop coroutine only.
     *
     * **Cleanups last, and remembered.** A cleanup is a terminal fact about a driver; a projection is a
     * snapshot of a driver that was live when it was taken. Draining cleanups last means the terminal
     * fact wins inside one drain, and [retiredDrivers] makes it win across drains: a projection a driver
     * enqueues after its cleanup was applied is dropped, so a retired driver is never routed again.
     * (Nothing depends on ordering *within* the projections: each is a whole set, so applying them in
     * any order leaves the map at the newest one per driver.)
     */
    fun drainRoutingQueues(): List<ConnectionIdKey> {
        val contested =
            when (val first = routeProjectionQueue.tryReceive().getOrNull()) {
                null -> emptyList()
                else -> applyProjections(first)
            }
        while (true) {
            val driver = driverCleanupQueue.tryReceive().getOrNull() ?: break
            retire(driver)
        }
        forgetExpiredRetirements()
        if (retiredPaths.isNotEmpty()) retiredPaths.removeAll { it.freeIdle() }
        return contested
    }

    /** Apply [first] and every projection queued behind it; the ids a second driver could not have. */
    private fun applyProjections(first: RouteProjection): List<ConnectionIdKey> {
        // Each projection is a whole set, so only a driver's newest matters.
        val newest = LinkedHashMap<QuicheDriver, RouteProjection>()
        newest[first.driver] = first
        while (true) {
            val projection = routeProjectionQueue.tryReceive().getOrNull() ?: break
            newest[projection.driver] = projection
        }
        val projections = newest.values.filter { it.driver !in retiredDrivers }
        // Every release before any claim: the same bytes re-issued by a later connection are claimed
        // in the same drain that releases them, in whichever order the queue holds the two projections.
        for (projection in projections) releaseDroppedIds(projection)
        val contested = mutableListOf<ConnectionIdKey>()
        for (projection in projections) claimIds(projection) { key -> contested += key }
        return contested
    }

    private fun forgetExpiredRetirements() {
        while (true) {
            val oldest = retirements.firstOrNull() ?: return
            if (!oldest.until.hasPassedNow()) return
            retirements.removeFirst()
            // Only what this retirement recorded: the id may have been closed again since, by a later
            // connection, and the driver retired again by its cleanup.
            for (key in oldest.closed) if (closedConnections[key] == oldest.until) closedConnections.remove(key)
            if (retiredDrivers[oldest.driver] == oldest.until) retiredDrivers.remove(oldest.driver)
        }
    }

    /**
     * The removal half of applying [projection]: unroute what its driver's last projection routed and this one
     * no longer lists. Removals are identity-checked, so a key another connection has since claimed
     * is left alone.
     */
    private fun releaseDroppedIds(projection: RouteProjection) {
        val driver = projection.driver
        val previous = projectedRoutes[driver] ?: return
        for (key in previous) {
            if (key !in projection.sourceIds && connectionsByDcid[key] === driver) connectionsByDcid.remove(key)
        }
    }

    /**
     * The addition half of applying [projection]: make its driver route its source ids, plus whatever
     * else was registered for it outside the projection (the accept-time handshake DCID — see
     * [projectedRoutes]).
     *
     * Idempotent by construction: replaying the same projection is a no-op and a projection that was
     * never applied is fully subsumed by the next.
     *
     * **Additions are identity-checked too.** A source id belongs to one connection, so an id another
     * live driver still routes, after every release of this drain, is not taken from it: the second
     * driver claiming the same bytes is the fault, and handing it the route would send the first
     * connection's datagrams to a driver that cannot read them, for as long as either lives. Each such
     * id is reported to [contested] and left with its first owner. (A second claim is only possible when
     * two ids were drawn equal, which is what a source of randomness shared across threads without
     * synchronisation does: see [connectionIdRandom].)
     */
    private fun claimIds(
        projection: RouteProjection,
        contested: (ConnectionIdKey) -> Unit,
    ) {
        val driver = projection.driver
        val claimed = HashSet<ConnectionIdKey>(projection.sourceIds.size)
        for (key in projection.sourceIds) {
            val holder = connectionsByDcid[key]
            if (holder != null && holder !== driver) {
                contested(key)
                continue
            }
            connectionsByDcid[key] = driver
            closedConnections.remove(key)
            claimed += key
        }
        projectedRoutes[driver] = claimed
    }

    // --- Live-driver ledger ---

    /**
     * Ledger [driver] BEFORE its run loop starts so the close sweep can never observe a started-but-
     * untracked driver. Additions happen only on the receive-loop coroutine, so they cease once close()
     * has joined the receive loop — the set is then stable for [reapAllDriversAndFreeRecvInfoCache].
     */
    fun trackLiveDriver(
        driver: QuicheDriver,
        replyAddresses: PathAddressTable,
    ) {
        liveDrivers.add(driver)
        pathsByDriver[driver] = ConnectionPaths(api, replyAddresses)
    }

    /** Called from a driver's `onCleanup` (after `run()` fully returns, so it can no longer connRecv). */
    fun untrackLiveDriver(driver: QuicheDriver) = liveDrivers.remove(driver)

    /**
     * The paths [driver]'s connection has been seen on. A driver routed without having been tracked
     * has paths whose reply addresses nothing reads. Receive-loop only.
     */
    fun pathsOf(driver: QuicheDriver): ConnectionPaths<K> = pathsByDriver.getOrPut(driver) { ConnectionPaths(api, PathAddressTable()) }

    // --- Teardown ---

    /**
     * The load-bearing close sweep. Call AFTER the platform has stopped and joined its receive loop
     * (so no new drivers are added and nothing else touches the routing map / cache) and BEFORE the
     * platform frees the shared `to` sockaddr or the native config.
     *
     * Destroys+joins EVERY driver in [liveDrivers] — a superset of [connectionsByDcid] — before freeing
     * any recv_info, so a driver dropped from the routing map but still draining buffered packets
     * can't `connRecv` a recv_info this sweep already freed. The [LiveDriverLedger.snapshot]
     * is race-safe against a driver concurrently removing itself via `onCleanup`. `destroy()` is
     * idempotent, so a driver that already finished is harmless here. [RecvInfoInFlightAtClose] is
     * unreachable given the join — thrown so any future regression trips deterministically instead
     * of as an opaque native connRecv SIGSEGV.
     */
    suspend fun reapAllDriversAndFreeRecvInfoCache() {
        for (driver in liveDrivers.snapshot()) {
            driver.destroy()
        }
        liveDrivers.clear()
        connectionsByDcid.clear()
        projectedRoutes.clear()
        registeredRoutes.clear()
        closedConnections.clear()
        retiredDrivers.clear()
        retirements.clear()
        for (paths in pathsByDriver.values) paths.freeAll()
        pathsByDriver.clear()
        for (paths in retiredPaths) paths.freeAll()
        retiredPaths.clear()
    }

    /** Close the hand-off + routing channels. Call after [reapAllDriversAndFreeRecvInfoCache]. */
    fun closeChannels() {
        acceptedDrivers.close()
        driverCleanupQueue.close()
        routeProjectionQueue.close()
    }

    /**
     * TEST SEAM (do not call in production): is [key] currently routed to a driver?
     *
     * Exists so a test can assert the *premise* it depends on rather than assume it — specifically
     * `RetiredCidInFlightPacketTestSuite`, whose whole construction is "the routing map still routes a
     * CID quiche has already forgotten". Without this the suite could go green because the packet it
     * released was dropped at the demux and never reached quiche at all, which is the same green a
     * working fix produces. Safe only while the receive loop is idle; the caller guarantees that.
     */
    internal fun routesForTest(key: ConnectionIdKey): Boolean = connectionsByDcid.containsKey(key)

    /**
     * TEST SEAM (do not call in production): drop every driver from the routing table without
     * destroying it, reproducing the state the cleanup-queue drain / trySend-failure removal create in
     * production — a *live* driver that is no longer routable. Lets a test assert the close sweep still
     * reaps such a driver (via [liveDrivers]) before it frees the recv_info cache. Safe only while the
     * receive loop is idle (the sole other writer of the map); the caller guarantees that.
     */
    internal fun deRouteAllDriversForTest() {
        connectionsByDcid.clear()
        projectedRoutes.clear()
        registeredRoutes.clear()
    }
}

/**
 * Decode a [QuicheApi.connReadSourceIds] / [QuicheApi.connDrainRetiredScids] readback buffer — [count]
 * slots of [RETIRED_SCID_SLOT_BYTES], each a length byte followed by that many id bytes — into routing
 * keys.
 *
 * Extracted rather than inlined at the one call site because this decode is where the layout can be
 * got wrong invisibly: a key built from the wrong offset is not a crash, it is a key that matches no
 * datagram, which looks exactly like "the peer never used that CID". `ConnectionIdKeyDecodeTests`
 * exercises it directly against a hand-built multi-slot buffer.
 *
 * A slot whose length byte is out of range (0, or beyond RFC 9000 §5.1's 20-byte maximum) is skipped:
 * the readback is our own buffer coming back out of quiche, not peer-framed input, so there is no
 * protocol violation to report — and a bad slot must not take the good ones with it.
 */
internal fun decodeConnectionIdSlots(
    slots: PlatformBuffer,
    count: Int,
): Set<ConnectionIdKey> {
    val ids = LinkedHashSet<ConnectionIdKey>(count)
    for (i in 0 until count) {
        val slot = i * RETIRED_SCID_SLOT_BYTES
        val length = slots[slot].toInt() and 0xFF
        // The id starts one byte past the slot, never at the slot itself — see ConnectionIdKey.from.
        if (length in 1..QUIC_MAX_CONN_ID_LEN) ids += ConnectionIdKey.from(slots, slot + 1, length)
    }
    return ids
}

/** One driver's retirement: the ids it [closed], recognised as recently closed [until] its draining period ends. */
internal class Retirement(
    val driver: QuicheDriver,
    val closed: List<ConnectionIdKey>,
    val until: TimeMark,
)

/** Where a datagram belongs, by its destination connection id. */
internal sealed interface DcidRoute {
    /** A connection that is open: hand the datagram to its [driver]. */
    class Live(
        val driver: QuicheDriver,
    ) : DcidRoute

    /** A connection this server closed within its draining period: an expected, late datagram. */
    data object RecentlyClosed : DcidRoute

    /** No connection this server knows: only a conforming Initial may create one. */
    data object Unknown : DcidRoute
}

/**
 * One driver's source-connection-id set as quiche reported it, on its way from the driver coroutine
 * that read it to the receive loop that owns the routing map.
 */
internal class RouteProjection(
    val driver: QuicheDriver,
    val sourceIds: Set<ConnectionIdKey>,
)

/**
 * The paths one accepted connection's datagrams have arrived on, each with the `recv_info` that tells
 * quiche the truth about it: a client's source address changes when it migrates (RFC 9000 §9), and
 * quiche needs the actual per-datagram source as `recv_info.from` to recognise the new path.
 *
 * Bounded at [MAX_PATHS] per connection, least recently used first, so the addresses a connection can
 * make the server remember do not grow with the datagrams sent at it — whoever sends them — and one
 * connection's paths are never evicted on another's account. A path evicted while quiche still uses
 * it is rebuilt by that path's next datagram.
 *
 * A recv_info is handed to the driver over an UNLIMITED command channel, so a lagging driver may
 * still hold one after its path went quiet; [CachedRecvInfo.inFlight] counts those references and
 * nothing here frees an entry that has any. Single-writer (receive loop); [CachedRecvInfo.inFlight]
 * and [replyAddresses] are what a driver coroutine touches.
 *
 * @param replyAddresses `sendInfo.to` → the address to send to, for this connection's egress channel:
 *   the peer half of every path held here, entered when the path is and removed with it.
 */
internal class ConnectionPaths<K>(
    private val api: QuicheApi,
    val replyAddresses: PathAddressTable,
) {
    // Insertion order is access order: a hit on any but the only entry is removed and re-inserted
    // (Kotlin/Native's LinkedHashMap has no accessOrder constructor).
    private val recvInfos = LinkedHashMap<K, CachedRecvInfo>()

    /**
     * The recv_info of the path [key] names, now the most recently used. A path this connection has
     * not been seen on gets the one [build] makes, and an idle entry is evicted once over [MAX_PATHS].
     * [build] also pins what the platform needs for it (the `from` sockaddr, the [replyAddresses]
     * entry), released by the [CachedRecvInfo] it returns.
     */
    inline fun recvInfoFor(
        key: K,
        build: () -> CachedRecvInfo,
    ): CachedRecvInfo {
        val held = recvInfos.size
        val cached = recvInfos.getOrPut(key, build)
        if (recvInfos.size == held) {
            markUsed(key, cached)
        } else if (recvInfos.size > MAX_PATHS) {
            freeLeastRecentlyUsedIdle()
        }
        return cached
    }

    private fun markUsed(
        key: K,
        cached: CachedRecvInfo,
    ) {
        if (recvInfos.size == 1) return
        recvInfos.remove(key)
        recvInfos[key] = cached
    }

    /**
     * Frees the least recently used entry no queued packet references. When every entry is referenced
     * the bound is exceeded until the next new path rather than a recv_info freed under a driver.
     */
    private fun freeLeastRecentlyUsedIdle() {
        val entries = recvInfos.values.iterator()
        while (entries.hasNext()) {
            val cached = entries.next()
            if (cached.inFlight.get() == 0) {
                // Unpublished before it is freed: a throw between the two must not leave a freed
                // handle where the next lookup hands it out.
                entries.remove()
                free(cached)
                return
            }
        }
    }

    /** Frees every entry no queued packet references; whether none are left. */
    fun freeIdle(): Boolean {
        val entries = recvInfos.values.iterator()
        while (entries.hasNext()) {
            val cached = entries.next()
            if (cached.inFlight.get() == 0) {
                entries.remove()
                free(cached)
            }
        }
        return recvInfos.isEmpty()
    }

    /** Frees every entry, once no driver can hold one. Throws [RecvInfoInFlightAtClose] if one does. */
    fun freeAll() {
        for (cached in recvInfos.values) {
            val inFlight = cached.inFlight.get()
            if (inFlight != 0) throw RecvInfoInFlightAtClose(inFlight)
        }
        val entries = recvInfos.values.iterator()
        while (entries.hasNext()) {
            val cached = entries.next()
            entries.remove()
            free(cached)
        }
    }

    private fun free(cached: CachedRecvInfo) {
        api.recvInfoFree(cached.info)
        cached.releaseSource()
    }

    companion object {
        /**
         * Paths remembered per connection. A connection has as many live paths as its peer has
         * connection ids to spend on them, [com.ditchoom.socket.quic.QuicOptions.activeConnectionIdLimit]
         * by default.
         */
        const val MAX_PATHS = 8
    }
}

/** A server closed while a driver still held [inFlight] references to one of its recv_infos. */
internal class RecvInfoInFlightAtClose(
    val inFlight: Int,
) : IllegalStateException("recv_info still in-flight at server close (inFlight=$inFlight)")

/**
 * One path's `recv_info`. [info] is the native handle; [releaseSource] frees the platform-pinned
 * `from` sockaddr it points at. [inFlight] counts references handed to drivers but not yet consumed,
 * guarding [ConnectionPaths] eviction/teardown against a use-after-free.
 */
internal class CachedRecvInfo(
    val info: QuicheRecvInfo,
    private val releaseSourceAddr: () -> Unit,
) {
    /** Packets handed to a driver but not yet consumed; guards against evict/free-while-in-use. */
    val inFlight = RecvInfoRefCount(0)

    /** Free the pinned per-source `from` sockaddr. Called after `recvInfoFree(info)`. */
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

    /**
     * The id in hex, so a failure that names a routing key says WHICH id — a default identity string
     * turns "the server is still routing X" into a sentence with no information in it. Rebuilt on each
     * call rather than cached: this runs in diagnostics, never on the demux path.
     */
    override fun toString(): String {
        val hex = StringBuilder(snapshot.limit() * 2)
        for (i in 0 until snapshot.limit()) hex.append((snapshot[i].toInt() and 0xFF).toString(16).padStart(2, '0'))
        return hex.toString()
    }

    companion object {
        /**
         * Snapshot [length] id bytes starting at **absolute index** [offset] in [buffer].
         *
         * [offset] is explicit and undefaulted on purpose. This reads by absolute index and ignores
         * the buffer's position, which is a trap when the source is a multi-slot readback buffer
         * (`connReadSourceIds` / `connDrainRetiredScids` lay ids out as a length byte followed by the
         * bytes, one slot per id). A caller that positioned the buffer at a slot and then passed only
         * a length silently snapshotted the *first* slot's length byte plus the first id's leading
         * bytes — a key that matches nothing. Requiring the offset makes that mistake unwritable
         * rather than merely fixed.
         */
        fun from(
            buffer: PlatformBuffer,
            offset: Int,
            length: Int,
        ): ConnectionIdKey {
            val snapshot = BufferFactory.managed().allocate(length)
            for (i in 0 until length) snapshot.writeByte(buffer[offset + i])
            snapshot.resetForRead()
            return ConnectionIdKey(snapshot)
        }
    }
}
