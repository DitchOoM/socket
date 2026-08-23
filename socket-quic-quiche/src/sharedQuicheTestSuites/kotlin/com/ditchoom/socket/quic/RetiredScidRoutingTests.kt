package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A connection ID the peer has retired must stop routing at the same instant quiche stops recognising
 * it (#437) — and, since #449, the routing table gets there by being **set to what quiche says**
 * rather than by replaying issue/retire events at it.
 *
 * The server's DCID→driver map is what decides whether a datagram reaches a connection at all, and it
 * used to keep every source CID for the connection's whole life while quiche dropped each one as the
 * peer retired it. That divergence is not benign: a packet sent legitimately *before* the peer retired
 * the CID can arrive after, because a RETIRE_CONNECTION_ID sent on a fast new path routinely overtakes
 * data still in flight on the slow old one. The map still routed it in; quiche no longer knew the CID,
 * reported `InvalidState`, and its `to_wire()` catch-all turned that into PROTOCOL_VIOLATION — killing
 * a healthy connection over a packet RFC 9000 §5.2.2 says to drop.
 *
 * Measured on a real Wi-Fi↔cellular handoff: the offending packet left the phone ~29ms *before* the
 * retirement it violated. No client can prevent that, so the receiving side has to tolerate it.
 *
 * ## What changed with the projection, and what these tests now pin
 * The first fix fed the map two event streams — an "issued" notification and a "retired" one — whose
 * relative order had to be reasoned about and either of which could leave the map permanently wrong if
 * dropped. These tests now pin the properties a projection has and a ledger cannot: it is idempotent,
 * it removes only the ids **it** placed (so the handshake DCID the client chose survives), it never
 * touches an id another connection has since claimed, and a driver cleanup outranks a projection taken
 * before it.
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin`
 * Same reason as [DriverCommandFailureTests]: this directory is `srcDir`'d into every platform test
 * source set plus `androidInstrumentedTest`, so one copy runs everywhere (DitchOoM/socket#390).
 */
class RetiredScidRoutingTests {
    private val bufferFactory = BufferFactory.deterministic()

    private fun driver(handle: Long): QuicheDriver =
        QuicheDriver(
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = StubQuicheApi(),
            conn = QuicheConn(handle),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(handle),
            sendInfo = QuicheSendInfo(handle),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = true,
        )

    private fun cid(vararg bytes: Int): ConnectionIdKey {
        val buf = bufferFactory.allocate(bytes.size)
        bytes.forEach { buf.writeByte(it.toByte()) }
        buf.resetForRead()
        return ConnectionIdKey.from(buf, offset = 0, length = bytes.size)
    }

    /**
     * **The #437 property, restated as a projection.** The peer retires a CID; quiche drops it from
     * `source_ids` in the same call that queues it as retired, so the very next projection simply does
     * not mention it — and the map must follow, without anyone having told it that a *retirement*
     * happened.
     */
    @Test
    fun anIdMissingFromTheNextProjectionStopsRouting() {
        val registry = ServerConnectionRegistry<String>(StubQuicheApi())
        val connection = driver(1L)
        val kept = cid(1, 2, 3, 4)
        val retired = cid(5, 6, 7, 8)

        registry.enqueueRouteProjection(connection, setOf(kept, retired))
        registry.drainRoutingQueues()
        assertSame(connection, registry.driverForDcid(kept), "a projected CID must route to its connection")
        assertSame(connection, registry.driverForDcid(retired), "a projected CID must route to its connection")

        registry.enqueueRouteProjection(connection, setOf(kept))
        registry.drainRoutingQueues()
        assertNull(
            registry.driverForDcid(retired),
            "a CID quiche no longer lists must stop routing — otherwise a packet still in flight when " +
                "the peer retired it reaches a quiche that no longer knows the CID, and it closes the " +
                "connection with PROTOCOL_VIOLATION (#437)",
        )
        assertSame(connection, registry.driverForDcid(kept), "the ids quiche still lists must keep routing")
    }

    /**
     * **A projection removes only what it placed.** A server routes the client's *original*
     * destination CID — chosen by the client during the handshake and registered at accept — and that
     * id is not one of our source ids (`Connection::source_ids()` is `ids.scids_iter()`), so quiche
     * never mentions it. A projection that removed every key it did not list would unroute the
     * connection mid-handshake and drop the client's Initial retransmissions.
     */
    @Test
    fun aProjectionNeverRemovesTheHandshakeDcidRegisteredAtAccept() {
        val registry = ServerConnectionRegistry<String>(StubQuicheApi())
        val connection = driver(1L)
        val handshakeDcid = cid(0xAA, 0xBB) // what the client put in its Initial packets
        val serverScid = cid(1, 1, 1, 1)

        registry.routeDriver(handshakeDcid, connection)
        registry.enqueueRouteProjection(connection, setOf(serverScid))
        registry.drainRoutingQueues()

        assertSame(
            connection,
            registry.driverForDcid(handshakeDcid),
            "the client's original destination CID was unrouted by a projection that could not know " +
                "about it — quiche has no opinion on an id the client chose, so the handshake would " +
                "stall on the first Initial retransmission",
        )
        assertSame(connection, registry.driverForDcid(serverScid), "the projected CID must route too")
    }

    /**
     * **Applying the same projection twice changes nothing.** The property the two event queues could
     * not have: a duplicate registration was harmless but a duplicate *retirement* was not, which is
     * why the old drain had to order additions before removals. A set-sync has no such ordering.
     */
    @Test
    fun replayingAProjectionIsANoOp() {
        val registry = ServerConnectionRegistry<String>(StubQuicheApi())
        val connection = driver(1L)
        val ids = setOf(cid(1), cid(2), cid(3))

        registry.enqueueRouteProjection(connection, ids)
        registry.enqueueRouteProjection(connection, ids)
        registry.enqueueRouteProjection(connection, ids)
        registry.drainRoutingQueues()

        for (id in ids) assertSame(connection, registry.driverForDcid(id), "a replayed projection unrouted a live id")
    }

    /**
     * **A stale projection never unroutes the connection that owns the id now.** A CID is unique per
     * connection while it is live, but the same bytes can be re-issued by a *later* connection. The
     * removal half of a projection is therefore identity-checked: it drops a key only while that key
     * still points at the projecting driver.
     */
    @Test
    fun aProjectionNeverUnroutesTheConnectionThatOwnsTheIdNow() {
        val registry = ServerConnectionRegistry<String>(StubQuicheApi())
        val previous = driver(1L)
        val current = driver(2L)
        val key = cid(9, 9, 9, 9)

        registry.enqueueRouteProjection(previous, setOf(key))
        registry.drainRoutingQueues()
        // The bytes are re-issued by a LATER connection, and only afterwards does the earlier
        // connection project a set that no longer contains them.
        registry.enqueueRouteProjection(current, setOf(key))
        registry.enqueueRouteProjection(previous, emptySet())
        registry.drainRoutingQueues()

        assertSame(
            current,
            registry.driverForDcid(key),
            "a projection dropped an id on bytes alone, unrouting a healthy connection because an " +
                "unrelated one had finished with the same id",
        )
    }

    /**
     * **A cleanup outranks a projection taken before it.** A cleanup is a terminal fact about a
     * driver; a projection is a snapshot of one that was live when it was read. Draining cleanups last
     * means a projection enqueued moments before a handler closed its connection cannot resurrect its
     * routes.
     */
    @Test
    fun aCleanupOutranksAProjectionEnqueuedBeforeIt() {
        val registry = ServerConnectionRegistry<String>(StubQuicheApi())
        val connection = driver(1L)
        val key = cid(4, 4)

        registry.enqueueRouteProjection(connection, setOf(key))
        registry.enqueueCleanup(connection)
        registry.drainRoutingQueues()

        assertNull(
            registry.driverForDcid(key),
            "a projection read just before the connection closed re-routed a dead driver",
        )
    }

    @Test
    fun theDriverDrainsOnlyWhenQuicheReportsRetiredIds() {
        val api = StubQuicheApi()

        // Nothing retired: the per-wake count read must not turn into a drain.
        api.retiredScidCount = 0
        assertEquals(0, api.connRetiredScids(QuicheConn(3L)))
        assertEquals(0, api.drainRetiredScidCalls, "a zero count must not cost a drain")

        // Something retired: the drain runs once and is sized from the count, so the ids cannot
        // overflow the buffer — `quiche_conn_retired_scid_iter` drains, and an id that does not fit is
        // gone from quiche forever.
        api.retiredScidCount = 3
        val out = bufferFactory.allocate(3 * RETIRED_SCID_SLOT_BYTES)
        try {
            api.connDrainRetiredScids(QuicheConn(3L), out.nativeMemoryAccess!!.nativeAddress.toLong(), 3)
        } finally {
            out.freeNativeMemory()
        }
        assertEquals(1, api.drainRetiredScidCalls)
        assertEquals(3, api.lastDrainCapacity, "the drain buffer must be sized from the reported count")
    }
}
