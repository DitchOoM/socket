package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/** Inert named transport — the cache only compares identity and never connects. */
private class StubTransport(
    private val name: String,
) : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream = throw UnsupportedOperationException("never connected in cache tests")

    override fun toString() = name
}

/**
 * [InMemoryCapabilityCache] semantics (RFC §6): demotions are TTL'd hints that heal on success, move
 * rungs to the back without removing them (still re-probed), and per-network entries die with the
 * network — the self-healing half of the cache-poisoning story ([FallbackTransportTest] covers the
 * never-poison half).
 */
class CapabilityCacheTest {
    private val quic = StubTransport("quic")
    private val tcp = StubTransport("tcp")
    private val ws = StubTransport("ws")
    private val chain = listOf(quic, tcp, ws)
    private val wifi = NetworkId.Link(NetworkKind.Wifi, handle = 1)
    private val cellular = NetworkId.Link(NetworkKind.Cellular, handle = 2)

    @Test
    fun perHostDemotionMovesRungToBackPreservingOrderWithinGroups() {
        val cache = InMemoryCapabilityCache()
        cache.recordUnsupported(CacheScope.PerHost, "h", wifi, quic)
        assertEquals(listOf(tcp, ws, quic), cache.order("h", wifi, chain), "demoted to back, never removed")
        assertEquals(chain, cache.order("other-host", wifi, chain), "per-host entry must not leak to another host")
    }

    @Test
    fun successHealsTheDemotion() {
        val cache = InMemoryCapabilityCache()
        cache.recordUnsupported(CacheScope.PerHost, "h", wifi, quic)
        cache.recordSuccess("h", wifi, quic)
        assertEquals(chain, cache.order("h", wifi, chain))
    }

    @Test
    fun demotionExpiresAfterTtlSoTheRungIsReProbed() {
        val time = TestTimeSource()
        val cache = InMemoryCapabilityCache(ttl = 10.minutes, timeSource = time)
        cache.recordUnsupported(CacheScope.PerHost, "h", wifi, quic)
        time += 9.minutes
        assertEquals(listOf(tcp, ws, quic), cache.order("h", wifi, chain), "still demoted inside TTL")
        time += 2.minutes
        assertEquals(chain, cache.order("h", wifi, chain), "TTL elapsed → automatic re-probe")
    }

    @Test
    fun perNetworkDemotionDiesWithTheNetwork() {
        val cache = InMemoryCapabilityCache()
        cache.recordUnsupported(CacheScope.PerNetwork, "h", wifi, quic)
        assertEquals(listOf(tcp, ws, quic), cache.order("h", wifi, chain), "demoted on the network that blocked it")
        // Wi-Fi → cellular: a different NetworkId has no entry, so QUIC is re-probed for free (RFC §6).
        assertEquals(chain, cache.order("h", cellular, chain))
    }

    @Test
    fun unidentifiedNetworkDisablesThePerNetworkScope() {
        val cache = InMemoryCapabilityCache()
        cache.recordUnsupported(CacheScope.PerNetwork, "h", NetworkId.Unidentified, quic)
        assertEquals(chain, cache.order("h", NetworkId.Unidentified, chain), "no identity → nothing recorded or read")
    }

    @Test
    fun scopeNoneRecordsNothing() {
        val cache = InMemoryCapabilityCache()
        cache.recordUnsupported(CacheScope.None, "h", wifi, quic)
        assertEquals(chain, cache.order("h", wifi, chain))
    }

    @Test
    fun perHostAndPerNetworkDemotionsCombine() {
        val cache = InMemoryCapabilityCache()
        cache.recordUnsupported(CacheScope.PerHost, "h", wifi, quic)
        cache.recordUnsupported(CacheScope.PerNetwork, "h", wifi, tcp)
        assertEquals(
            listOf(ws, quic, tcp),
            cache.order("h", wifi, chain),
            "both scopes demote; original order kept within the demoted group",
        )
    }

    @Test
    fun concurrentReadersAndWritersNeverCorruptTheCache() =
        runTest {
            // The cache is FallbackTransport's production default, so concurrent connects hammer it from
            // real threads (JVM/native; single-threaded no-op on JS). Copy-on-write must survive without
            // corruption and end in a consistent, healable state.
            val cache = InMemoryCapabilityCache()
            withContext(Dispatchers.Default) {
                repeat(8) { worker ->
                    launch {
                        repeat(200) { i ->
                            val host = "h${(worker + i) % 4}"
                            cache.recordUnsupported(CacheScope.PerHost, host, wifi, quic)
                            cache.recordUnsupported(CacheScope.PerNetwork, host, wifi, tcp)
                            cache.order(host, wifi, chain)
                            cache.recordSuccess(host, wifi, quic)
                        }
                    }
                }
            }
            repeat(4) { cache.recordSuccess("h$it", wifi, tcp) }
            repeat(4) { cache.recordSuccess("h$it", wifi, quic) }
            repeat(4) { assertEquals(chain, cache.order("h$it", wifi, chain), "all demotions must heal cleanly") }
        }
}
