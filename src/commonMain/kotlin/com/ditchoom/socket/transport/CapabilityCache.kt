package com.ditchoom.socket.transport

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
 * The no-memory cache: every connect tries the full chain in its declared order. The v2 default is
 * [InMemoryCapabilityCache]; pass this to opt out of learning entirely (e.g. for deterministic
 * benchmarking or tests that script exact rung order).
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
 * Thread-safety: this is [FallbackTransport]'s production default, so concurrent connects (possibly
 * on different threads) may hit it simultaneously. State is one immutable **flat list** of demotion
 * entries swapped whole by CAS — reads never lock, and a racing write costs one retry, never
 * corruption. A list, deliberately *not* copy-on-write maps: Kotlin's `HashMap` lazily creates and
 * caches its `entries`/`keys` views on first read, so even "read-only" concurrent access to a shared
 * map is a data race (it SIGSEGVs under load on Kotlin/Native's weak memory ordering) — `ArrayList`
 * reads are genuinely pure. The set is tiny (bounded by chain length × recently-failed hosts/
 * networks, pruned on write by TTL), so linear scans beat the maps anyway. Semantically the entries
 * stay hints: a demotion lost to a concurrent success-heal is one extra probe, not an error.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryCapabilityCache(
    private val ttl: Duration = 10.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : CapabilityCache {
    /** One demotion: keyed by exactly one of [host] (per-host scope) or [networkId] (per-network scope). */
    private class Demotion(
        val host: String?,
        val networkId: NetworkId?,
        val transport: Transport,
        val mark: TimeMark,
    )

    private val state = AtomicReference(emptyList<Demotion>())

    override fun order(
        host: String,
        networkId: NetworkId,
        chain: List<Transport>,
    ): List<Transport> {
        var demoted: MutableSet<Transport>? = null
        for (entry in state.load()) {
            if (entry.mark.elapsedNow() >= ttl) continue // expired → not active (TTL → automatic re-probe)
            if (entry.host == host || (entry.networkId != null && entry.networkId == networkId)) {
                (demoted ?: HashSet<Transport>().also { demoted = it }) += entry.transport
            }
        }
        val down = demoted ?: return chain
        val (back, front) = chain.partition { it in down } // partition is order-preserving in both lists
        return front + back
    }

    override fun recordSuccess(
        host: String,
        networkId: NetworkId,
        transport: Transport,
    ) = update { entries ->
        entries.filter { entry ->
            entry.transport != transport ||
                !(entry.host == host || (entry.networkId != null && entry.networkId == networkId))
        }
    }

    override fun recordUnsupported(
        scope: CacheScope,
        host: String,
        networkId: NetworkId,
        transport: Transport,
    ) {
        val entry =
            when (scope) {
                CacheScope.PerHost -> Demotion(host, null, transport, timeSource.markNow())
                CacheScope.PerNetwork ->
                    when (networkId) {
                        // No cheap network identity → the per-network scope is simply off (RFC §12).
                        NetworkId.Unidentified -> return
                        is NetworkId.KindOnly, is NetworkId.Link ->
                            Demotion(null, networkId, transport, timeSource.markNow())
                    }
                CacheScope.None -> return
            }
        update { entries ->
            // Writes are the pruning point (reads never mutate): drop expired entries and the stale
            // version of this same demotion, then append the fresh mark.
            entries.filter {
                it.mark.elapsedNow() < ttl &&
                    !(it.transport == transport && it.host == entry.host && it.networkId == entry.networkId)
            } + entry
        }
    }

    private inline fun update(transform: (List<Demotion>) -> List<Demotion>) {
        while (true) {
            val current = state.load()
            if (state.compareAndSet(current, transform(current))) return
        }
    }
}
