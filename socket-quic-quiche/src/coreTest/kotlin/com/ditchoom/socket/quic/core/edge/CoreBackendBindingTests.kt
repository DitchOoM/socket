@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.PeerMigrationPermission
import com.ditchoom.socket.quic.PeerTransportParams
import com.ditchoom.socket.quic.QuicActivePathStats
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.migrationPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What each platform's quiche binding decodes for the core, on a real loopback pair on every platform: the path events
 * quiche raises when path MTU discovery raises a path's MTU, and the layout of the peer's transport parameters. The
 * bindings read these separately (JNI arrays, FFM offsets, a generated cinterop struct), so each backend runs both.
 */
class CoreBackendBindingTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** The client's handshake-complete reading of the peer's transport parameters, taken on the loop that read the datagram. */
    private class TakesParams(
        private val real: QuicheApi,
        val taken: AtomicReference<PeerTransportParams>,
    ) : QuicheApi by real {
        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            recvInfo: QuicheRecvInfo,
        ): Int {
            val read = real.connRecv(conn, buf, bufLen, recvInfo)
            if (taken.load() is PeerTransportParams.NotYetNegotiated && real.connIsEstablished(conn)) {
                taken.store(real.connPeerTransportParams(conn))
            }
            return read
        }
    }

    /** A link of 1280 bytes: a datagram over it is lost, as a path refuses it with DF set; the others go on. */
    private class SmallLink(
        private val real: DatagramBatchIo,
    ) : DatagramBatchIo by real {
        /** One single-datagram batch per lane, each used by the one thread that owns the lane. */
        private val single = arrayOfNulls<SendBatch>(LANES)

        override fun send(batch: SendBatch): SendBatchResult {
            val one =
                single[batch.lane] ?: run {
                    val bytes = nativeBytes(batch.limits.maxDatagramSize)
                    SendBatch(bytes, BatchLimits(1, batch.limits.maxDatagramSize), batch.lane).also { single[batch.lane] = it }
                }
            for (n in 0 until batch.pending) {
                if (batch.lengthOf(n) > LINK) continue
                one.storage.copyFrom(batch.storage, batch.offsetOf(n), 0, batch.lengthOf(n))
                one.commit(batch.lengthOf(n), batch.targetOf(n))
                real.send(one)
                one.took(one.pending)
            }
            return SendBatchResult.AllTaken
        }
    }

    /**
     * Path MTU discovery starts every path at 1200 bytes and quiche raises a path event for each validated change; a
     * backend that cannot decode one ends the connection the first time a probe is acknowledged. Over a link of 1280
     * bytes the probes above it are lost and the ones below are acknowledged, so the path settles between: the witness
     * that a path event was raised and drained is the path's MTU above 1200 and the connection still echoing. A
     * connection that did not probe would have its 1350-byte datagrams lost and never echo.
     */
    @Test
    fun aConnectionKeepsWorkingAfterPathMtuDiscoveryRaisesThePathMtu() =
        coreTest("CoreBackendBindingTests.aConnectionKeepsWorkingAfterPathMtuDiscoveryRaisesThePathMtu") {
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops, wrapIo = { SmallLink(it) })
                try {
                    val bulk = Random(MTU_SEED).nextBytes(BULK)
                    assertContentEquals(bulk, echoOver(client.connection.openStream(), bulk), "the echo that drives the probes")
                    val mtu = withTimeout(MTU_WAIT) { mtuAbove(client.connection, MIN_PATH_MTU) }
                    assertTrue(
                        mtu in MIN_PATH_MTU + 1..LINK.toLong(),
                        "discovery settled the path at $mtu, between the minimum and the link",
                    )
                    val after = Random(MTU_SEED + 1).nextBytes(SMALL)
                    assertContentEquals(after, echoOver(client.connection.openStream(), after), "the connection survived the path event")
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    /**
     * The peer's transport parameters are read at the offsets the header declares. The two fields beside
     * `disable_active_migration` are the guard against quiche's struct losing its `#[repr(C)]` (it did, upstream):
     * rustc reorders it, the active connection id limit reads back -1 and the datagram frame size 0, and the flag
     * answers forbidden on every connection, which silently switches migration off. Values that are not the defaults,
     * so a zeroed or stale struct cannot match.
     */
    @Test
    fun thePeersTransportParametersAreReadAtTheOffsetsTheHeaderDeclares() =
        coreTest("CoreBackendBindingTests.thePeersTransportParametersAreReadAtTheOffsetsTheHeaderDeclares") {
            val options = coreTestOptions.copy(idleTimeout = 10.seconds, activeConnectionIdLimit = ACTIVE_ID_LIMIT)
            val taken = AtomicReference<PeerTransportParams>(PeerTransportParams.NotYetNegotiated)
            withCoreEchoServer(options = options) { server ->
                val client = connectCore(server.port, loops = loops, options = options, api = TakesParams(platformQuicheApi(), taken))
                try {
                    val negotiated =
                        assertIs<PeerTransportParams.Negotiated>(taken.load(), "the handshake completed, so the parameters were processed")
                    assertEquals(options.idleTimeout.inWholeMilliseconds, negotiated.maxIdleTimeoutMillis, "max_idle_timeout (offset 0)")
                    assertEquals(options.flowControl.initialMaxData, negotiated.initialMaxData, "initial_max_data (offset 16)")
                    assertEquals(ACTIVE_ID_LIMIT, negotiated.activeConnIdLimit, "active_conn_id_limit (offset 88)")
                    assertEquals(-1L, negotiated.maxDatagramFrameSize, "max_datagram_frame_size (offset 96): -1 with datagrams off")
                    assertEquals(false, negotiated.disableActiveMigration, "disable_active_migration (offset 80)")
                    assertEquals(PeerMigrationPermission.Permitted, negotiated.migrationPermission, "the loopback peer permits migration")
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    /** The client's active path MTU once it is above [floor]; polled, as the probes are acknowledged on the loop. */
    private suspend fun mtuAbove(
        connection: CoreConnection,
        floor: Long,
    ): Long {
        while (true) {
            val stats = assertIs<QuicStatsRead.Read>(connection.stats()).stats
            val path = stats.activePath
            if (path is QuicActivePathStats.Measured && path.pathMtuBytes > floor) return path.pathMtuBytes
            delay(POLL)
        }
    }

    private companion object {
        const val MTU_SEED = 388
        const val LANES = 64
        const val LINK = 1280

        /** The 1200 bytes every QUIC path carries. */
        const val MIN_PATH_MTU = 1200L
        const val BULK = 256 * 1024
        const val SMALL = 512
        const val ACTIVE_ID_LIMIT = 7L
        val MTU_WAIT = 10.seconds
        val POLL = 20.milliseconds
    }
}
