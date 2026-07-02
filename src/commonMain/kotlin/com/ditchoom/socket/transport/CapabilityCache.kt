package com.ditchoom.socket.transport

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Remembers which transports failed where, so future connects skip straight past a rung a server or
 * network has already shown it can't do (RFC_TRANSPORT_FALLBACK §6). Two scopes ([CacheScope]):
 * per-host ("*this server* doesn't speak QUIC") and per-network ("*this path* blocks UDP"). Every
 * demotion is a **hint, not a hard exclusion** — TTL'd and healed by a later success — so a server
 * that enables a transport later, or a fluke demotion, recovers on its own.
 */
interface CapabilityCache {
    /**
     * Reorder [chain] for this (host, [networkId]): active demotions move to the back (kept, never
     * removed, so they are still re-probed). Order within each group is preserved.
     */
    fun order(
        host: String,
        networkId: NetworkId,
        chain: List<Transport>,
    ): List<Transport>

    /** A working connect heals any demotion of [transport] for this host/network. */
    fun recordSuccess(
        host: String,
        networkId: NetworkId,
        transport: Transport,
    )

    /** Remember [transport] as unsupported at [scope] (only [FallbackVerdict.cacheUnsupported] verdicts). */
    fun recordUnsupported(
        scope: CacheScope,
        host: String,
        networkId: NetworkId,
        transport: Transport,
    )
}

/**
 * MVP default (RFC §11 puts the learning cache in v2): no memory — every connect tries the full chain
 * in its declared order.
 */
object NoOpCapabilityCache : CapabilityCache {
    override fun order(
        host: String,
        networkId: NetworkId,
        chain: List<Transport>,
    ): List<Transport> = chain

    override fun recordSuccess(
        host: String,
        networkId: NetworkId,
        transport: Transport,
    ) = Unit

    override fun recordUnsupported(
        scope: CacheScope,
        host: String,
        networkId: NetworkId,
        transport: Transport,
    ) = Unit
}

/**
 * In-memory two-scope cache (RFC §6). Demotions expire after [ttl] and a success heals them, so a
 * fluke never permanently avoids a good transport. Per-network entries key on the typed [NetworkId],
 * so they are invalidated for free when the network changes (a different [NetworkId] has no entry);
 * [NetworkId.Unidentified] disables the per-network scope.
 *
 * [timeSource] is injected for deterministic TTL tests (`kotlin.time.TestTimeSource`).
 *
 * Thread-safety: entries are hints, so a race only costs one extra probe or one skipped demotion —
 * never correctness. Kept lock-free for the prototype; wrap in a `Mutex` if strict consistency is
 * ever wanted.
 */
class InMemoryCapabilityCache(
    private val ttl: Duration = 10.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : CapabilityCache {
    private val perHost = HashMap<String, HashMap<Transport, TimeMark>>()
    private val perNetwork = HashMap<NetworkId, HashMap<Transport, TimeMark>>()

    override fun order(
        host: String,
        networkId: NetworkId,
        chain: List<Transport>,
    ): List<Transport> {
        val demoted = demotedFor(host, networkId)
        if (demoted.isEmpty()) return chain
        val (down, up) = chain.partition { it in demoted } // partition is order-preserving in both lists
        return up + down
    }

    override fun recordSuccess(
        host: String,
        networkId: NetworkId,
        transport: Transport,
    ) {
        perHost[host]?.remove(transport)
        perNetwork[networkId]?.remove(transport)
    }

    override fun recordUnsupported(
        scope: CacheScope,
        host: String,
        networkId: NetworkId,
        transport: Transport,
    ) {
        when (scope) {
            CacheScope.PerHost ->
                perHost.getOrPut(host) { HashMap() }[transport] = timeSource.markNow()
            CacheScope.PerNetwork ->
                when (networkId) {
                    // No cheap network identity → the per-network scope is simply off (RFC §12).
                    NetworkId.Unidentified -> Unit
                    is NetworkId.KindOnly, is NetworkId.Link ->
                        perNetwork.getOrPut(networkId) { HashMap() }[transport] = timeSource.markNow()
                }
            CacheScope.None -> Unit
        }
    }

    private fun demotedFor(
        host: String,
        networkId: NetworkId,
    ): Set<Transport> {
        val active = HashSet<Transport>()
        collectActive(perHost[host], active)
        if (networkId != NetworkId.Unidentified) collectActive(perNetwork[networkId], active)
        return active
    }

    /** Collect still-live demotions; opportunistically drop expired ones (TTL → automatic re-probe). */
    private fun collectActive(
        entries: HashMap<Transport, TimeMark>?,
        into: MutableSet<Transport>,
    ) {
        if (entries == null) return
        val expired = ArrayList<Transport>()
        for ((transport, mark) in entries) {
            if (mark.elapsedNow() < ttl) into += transport else expired += transport
        }
        expired.forEach { entries.remove(it) }
    }
}
