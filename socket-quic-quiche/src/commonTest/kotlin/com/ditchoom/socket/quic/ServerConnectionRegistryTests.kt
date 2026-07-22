package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure, cross-platform unit tests for [ServerConnectionRegistry] — the bookkeeping layer extracted
 * from the three per-platform quiche servers (JVM/Linux/Apple). No native quiche, no UDP socket, no
 * gated run loop: the registry's invariants are exercised directly with [StubQuicheApi] and
 * unstarted [QuicheDriver]s (a driver's [QuicheDriver.destroy] on an un-`start`ed driver is just
 * `commands.close()`, so it's a cheap, side-effect-free stand-in for a live driver here).
 *
 * These complement the integration-level `ServerCloseRecvInfoRaceTest` (jvmTest, real server + gated
 * connRecv): that proves the JVM server *wires* the registry correctly; these prove the registry
 * *logic* on every target, and give the #179 recv_info-UAF invariant a fast deterministic home.
 */
class ServerConnectionRegistryTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Counts `recvInfoFree` so a test can assert the cache was actually freed by the close sweep. */
    private class CountingFreeApi(
        delegate: QuicheApi,
    ) : QuicheApi by delegate {
        var recvInfoFrees = 0
            private set

        override fun recvInfoFree(info: QuicheRecvInfo) {
            recvInfoFrees++
        }
    }

    /** An accepted-but-unstarted driver — a valid stand-in for a live one in the ledger/routing map. */
    private fun idleDriver(api: QuicheApi = StubQuicheApi()): QuicheDriver =
        QuicheDriver(
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = true,
        )

    /** A distinct [ConnectionIdKey] from the given CID bytes. */
    private fun cid(vararg bytes: Int): ConnectionIdKey {
        val buf: PlatformBuffer = bufferFactory.allocate(bytes.size)
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return ConnectionIdKey.from(buf, bytes.size)
    }

    // ── The #179 invariant: the close sweep reaps EVERY live driver, not just routable ones ──

    @Test
    fun reapDestroysDeRoutedButStillLiveDriver() =
        runQuicTest {
            val registry = ServerConnectionRegistry<Int>(StubQuicheApi())
            val routed = idleDriver()
            val deRouted = idleDriver()
            // Both are accepted (ledgered) and routing.
            registry.trackLiveDriver(routed)
            registry.routeDriver(cid(1), routed)
            registry.trackLiveDriver(deRouted)
            registry.routeDriver(cid(2), deRouted)

            // Reproduce the production hazard: a driver stops routing (trySend-failure removal /
            // cleanup-queue drain) but its run loop is still live — it stays in the ledger.
            registry.deRouteDriver(deRouted)
            assertNull(registry.driverForDcid(cid(2)), "de-routed driver is no longer routable")
            assertSame(routed, registry.driverForDcid(cid(1)), "the other driver still routes")

            registry.reapAllDriversAndFreeRecvInfoCache()

            assertTrue(routed.commands.isClosedForSend, "routed driver was destroyed")
            assertTrue(
                deRouted.commands.isClosedForSend,
                "de-routed-but-live driver was ALSO destroyed — the #179 recv_info-UAF invariant " +
                    "(sweeping the ledger, a superset of the routing table, not the routing table)",
            )
        }

    // ── The tripwire: freeing a recv_info still in flight fails loudly, not as a native SIGSEGV ──

    @Test
    fun reapTripwireFiresWhenACachedRecvInfoIsStillInFlight() =
        runQuicTest {
            val api = CountingFreeApi(StubQuicheApi())
            val registry = ServerConnectionRegistry<Int>(api)
            val cached = registry.putRecvInfo(7, QuicheRecvInfo(1L)) { }
            // Simulate a lagging driver still holding a reference to the cached recv_info.
            cached.inFlight.incrementAndGet()

            val error =
                assertFailsWith<IllegalStateException> {
                    registry.reapAllDriversAndFreeRecvInfoCache()
                }
            assertTrue(
                error.message?.contains("in-flight") == true,
                "tripwire message names the in-flight recv_info, got: ${error.message}",
            )
            // The offending entry must NOT have been freed (that would be the UAF).
            assertEquals(0, api.recvInfoFrees, "a still-in-flight recv_info is never freed")
        }

    // ── Clean close frees every cached recv_info and releases each pinned source sockaddr ──

    @Test
    fun reapFreesEveryCachedRecvInfoAndReleasesItsSourceAddr() =
        runQuicTest {
            val api = CountingFreeApi(StubQuicheApi())
            val registry = ServerConnectionRegistry<Int>(api)
            var released = 0
            registry.putRecvInfo(1, QuicheRecvInfo(1L)) { released++ }
            registry.putRecvInfo(2, QuicheRecvInfo(2L)) { released++ }

            registry.reapAllDriversAndFreeRecvInfoCache()

            assertEquals(2, api.recvInfoFrees, "every cached recv_info struct was freed")
            assertEquals(2, released, "every pinned per-source `from` sockaddr was released")
            assertNull(registry.lookupRecvInfo(1), "cache is cleared after the sweep")
        }

    // ── recv_info cache: a hit promotes to most-recently-used and returns the same entry ──

    @Test
    fun lookupRecvInfoReturnsTheCachedEntryOnHitAndNullOnMiss() =
        runQuicTest {
            val registry = ServerConnectionRegistry<Int>(StubQuicheApi())
            assertNull(registry.lookupRecvInfo(42), "miss before any put")
            val cached = registry.putRecvInfo(42, QuicheRecvInfo(1L)) { }
            assertSame(cached, registry.lookupRecvInfo(42), "hit returns the same cached entry")
            assertNull(registry.lookupRecvInfo(99), "unrelated key still misses")
        }

    // ── Routing: de-routing a driver removes ALL of its entries (SCID + every DCID), only its own ──

    @Test
    fun deRouteDriverRemovesEveryEntryForThatDriverOnly() =
        runQuicTest {
            val registry = ServerConnectionRegistry<Int>(StubQuicheApi())
            val driver = idleDriver()
            val other = idleDriver()
            registry.routeDriver(cid(0xAA), driver) // server SCID
            registry.routeDriver(cid(0xBB), driver) // client's DCID
            registry.routeDriver(cid(0xCC), other)

            registry.deRouteDriver(driver)

            assertNull(registry.driverForDcid(cid(0xAA)), "driver's SCID entry removed")
            assertNull(registry.driverForDcid(cid(0xBB)), "driver's DCID entry removed")
            assertSame(other, registry.driverForDcid(cid(0xCC)), "the other driver is untouched")
        }

    // ── Routing queues: cleanup removals are applied before spare-SCID additions ──

    @Test
    fun drainRoutingQueuesAppliesCleanupThenScidRegistrations() =
        runQuicTest {
            val registry = ServerConnectionRegistry<Int>(StubQuicheApi())
            val driver = idleDriver()
            registry.routeDriver(cid(1), driver)

            registry.enqueueCleanup(driver) // handler closed it → drop its routes
            registry.enqueueScidRegistration(cid(2), driver) // driver issued a spare SCID → add route

            registry.drainRoutingQueues()

            assertNull(registry.driverForDcid(cid(1)), "cleanup removed the old route")
            assertSame(driver, registry.driverForDcid(cid(2)), "spare-SCID registration was applied")
        }
}
