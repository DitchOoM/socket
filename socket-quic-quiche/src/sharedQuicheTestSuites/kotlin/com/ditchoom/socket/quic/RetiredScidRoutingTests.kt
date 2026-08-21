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
 * it (#437).
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
        return ConnectionIdKey.from(buf, bytes.size)
    }

    @Test
    fun aRetiredConnectionIdStopsRouting() {
        val registry = ServerConnectionRegistry<String>(StubQuicheApi())
        val connection = driver(1L)
        val key = cid(1, 2, 3, 4)

        registry.enqueueScidRegistration(key, connection)
        registry.drainRoutingQueues()
        assertSame(connection, registry.driverForDcid(key), "an issued CID must route to its connection")

        registry.enqueueScidRetirement(key, connection)
        registry.drainRoutingQueues()
        assertNull(
            registry.driverForDcid(key),
            "a retired CID must stop routing — otherwise a packet still in flight when the peer " +
                "retired it reaches a quiche that no longer knows the CID, and it closes the " +
                "connection with PROTOCOL_VIOLATION (#437)",
        )
    }

    @Test
    fun aRetirementNeverUnroutesTheConnectionThatOwnsTheIdNow() {
        val registry = ServerConnectionRegistry<String>(StubQuicheApi())
        val previous = driver(1L)
        val current = driver(2L)
        val key = cid(9, 9, 9, 9)

        // The bytes are re-registered by a LATER connection before the earlier one's retirement is
        // drained. Removing by key alone would unroute a healthy connection because an unrelated one
        // had finished with the same id.
        registry.enqueueScidRegistration(key, previous)
        registry.enqueueScidRetirement(key, previous)
        registry.enqueueScidRegistration(key, current)
        registry.drainRoutingQueues()

        assertSame(
            current,
            registry.driverForDcid(key),
            "a retirement must be matched on connection identity, not on the id alone",
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
