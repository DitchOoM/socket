@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **When** the driver republishes its source-connection-id set, and when it must not.
 *
 * The routing table being a projection of `quiche_conn_source_ids` (#449) is only affordable if the
 * driver does not read and rebuild that set on every wake — an established connection wakes on every
 * datagram, and a per-wake allocation plus a set snapshot across a coroutine hop would be a real cost
 * on a hot path. The design instead re-reads on three signals: this driver issued a CID, the peer
 * retired one, or quiche's count disagrees with what was last published. In the steady state that is a
 * single `quiche_conn_active_scids` integer read.
 *
 * The third signal is the one that makes this a projection rather than a smarter ledger: it needs no
 * event at all. If the two views drift for any reason — a retirement quiche performed on its own via
 * `retire_prior_to`, a projection that never reached the receive loop, a signal nobody predicted — the
 * next wake notices and repairs it. A ledger could only ever be as right as the events it was fed.
 *
 * [StubQuicheApi] models the *policy* here and writes no connection-ID bytes, exactly as it does for
 * the retired-id drain. The bytes are covered where they mean something: `ConnectionIdSlotDecodeTests`
 * against a hand-built buffer, and `SourceIdReadbackTestSuite` against real quiche on every platform.
 */
class SourceIdProjectionPolicyTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Records what the driver published, and how often. */
    private class RecordingSink : SourceIdSink {
        var publications = 0
            private set
        var lastCount = -1
            private set

        override fun replaceRoutes(
            ids: com.ditchoom.buffer.PlatformBuffer,
            count: Int,
        ) {
            publications++
            lastCount = count
        }
    }

    private inner class Fixture(
        val sink: SourceIdSink?,
    ) {
        val stub = StubQuicheApi().apply { established = true }

        val driver =
            QuicheDriver(
                migration = MigrationCapability.ServerConnection,
                rawApi = stub,
                conn = QuicheConn(1L),
                bufferFactory = bufferFactory,
                recvInfo = QuicheRecvInfo(1L),
                sendInfo = QuicheSendInfo(1L),
                udpChannel = StubUdpChannel(),
                clientMode = false,
                isServer = true,
                driverContext = EmptyCoroutineContext,
                onSourceIds = sink,
            )

        /** One benign driver-loop wake (a no-op stream open), so `updateState` runs again. */
        suspend fun wake() {
            driver.commands.send(QuicheCmd.OpenStream(CompletableDeferred()))
        }
    }

    /**
     * The set is published once it is known, and **not again on a quiet wake**. The second half is the
     * cost claim: five more wakes with nothing changed must not re-read or re-publish anything.
     */
    @Test
    fun theSetIsPublishedOnceAndNotRepublishedOnAQuietWake() =
        runTest {
            val sink = RecordingSink()
            val f = Fixture(sink)
            f.stub.activeScidCount = 3
            f.stub.sourceIdsYielded = 3
            f.driver.start(this)
            try {
                runCurrent()
                assertEquals(1, sink.publications, "the established driver never published its source ids")
                assertEquals(3, sink.lastCount, "the projection must carry every id quiche listed")
                val readsAfterFirst = f.stub.readSourceIdCalls

                repeat(5) {
                    f.wake()
                    runCurrent()
                }

                assertEquals(1, sink.publications, "a quiet wake republished the set — this runs on every datagram")
                assertEquals(
                    readsAfterFirst,
                    f.stub.readSourceIdCalls,
                    "a quiet wake re-read quiche's source ids, which allocates a slot buffer per wake",
                )
                assertTrue(
                    f.stub.activeScidCalls > readsAfterFirst,
                    "the steady state should still cost one connActiveScids read per wake — that read is what " +
                        "lets the projection notice a drift nobody signalled",
                )
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **The self-correcting signal.** quiche's count shrinks with nothing else to announce it — no CID
     * issued here, no retirement drained — and the next wake still republishes. This is the property a
     * ledger cannot have.
     */
    @Test
    fun aCountThatDisagreesRepublishesWithNoEventAtAll() =
        runTest {
            val sink = RecordingSink()
            val f = Fixture(sink)
            f.stub.activeScidCount = 3
            f.stub.sourceIdsYielded = 3
            f.driver.start(this)
            try {
                runCurrent()
                assertEquals(1, sink.publications)

                // The peer retired one and quiche dropped it from its table, but nothing told us.
                f.stub.activeScidCount = 2
                f.stub.sourceIdsYielded = 2
                f.wake()
                runCurrent()

                assertEquals(2, sink.publications, "quiche's set shrank and the routing table was never told")
                assertEquals(2, sink.lastCount, "the republished projection must be the NEW set, not the old one")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * **A half-bound backend must not unroute the connection.** [QuicheApi.connActiveScids] and
     * [QuicheApi.connReadSourceIds] both default to "nothing", so a backend that bound one and not the
     * other reports a set that exists and then yields none of it. Publishing that empty answer would
     * remove every route the connection has — the exact opposite of what a projection is for.
     */
    @Test
    fun aBackendThatYieldsNoIdsNeverPublishesAnEmptySet() =
        runTest {
            val sink = RecordingSink()
            val f = Fixture(sink)
            f.stub.activeScidCount = 3
            f.stub.sourceIdsYielded = 0 // the QuicheApi default: this half was never bound
            f.driver.start(this)
            try {
                runCurrent()
                f.wake()
                runCurrent()

                assertEquals(
                    0,
                    sink.publications,
                    "an empty projection was published over live routes — every id this connection has " +
                        "would stop routing, and its next datagram would miss the demux entirely",
                )
                assertTrue(f.stub.readSourceIdCalls > 0, "the driver never even tried to read — the test proved nothing")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * A backend with no readback at all (both halves answering the [QuicheApi] default) publishes
     * nothing and does not even reach the read. The interface default must never masquerade as "this
     * connection has no connection IDs".
     */
    @Test
    fun anUnboundReadbackPublishesNothing() =
        runTest {
            val sink = RecordingSink()
            val f = Fixture(sink)
            f.driver.start(this)
            try {
                runCurrent()
                f.wake()
                runCurrent()

                assertEquals(0, sink.publications, "a backend reporting no active CIDs published a projection anyway")
                assertEquals(0, f.stub.readSourceIdCalls, "a zero count must not cost a read")
            } finally {
                f.driver.destroy()
            }
        }

    /**
     * A client has no DCID map to keep in step — it demuxes by per-path socket — so it must not pay
     * even the count read. Asserting on the *call count* rather than on the absence of a sink is what
     * makes this about cost and not about a null check.
     */
    @Test
    fun aConnectionWithNoSinkNeverReadsQuichesCidTable() =
        runTest {
            val f = Fixture(sink = null)
            f.stub.activeScidCount = 3
            f.stub.sourceIdsYielded = 3
            f.driver.start(this)
            try {
                runCurrent()
                repeat(3) {
                    f.wake()
                    runCurrent()
                }
                assertEquals(
                    0,
                    f.stub.activeScidCalls,
                    "a connection with no routing table to project onto still read quiche's CID table on " +
                        "every wake",
                )
            } finally {
                f.driver.destroy()
            }
        }
}
