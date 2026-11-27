package com.ditchoom.socket.quic

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [QuicheApi.connActivePathProbes] on a real quiche pair, on each backend's own binding: after a migration and a run of
 * unanswered PTOs, the one Long names the path quiche flags active (index 1, not 0) and that path's `total_pto_count`,
 * exactly as [QuicheApi.connPathStats] reads them. Both halves of the packing are non-zero, so a binding that drops
 * either (a JNI shim whose return type changed resolves by short name and returns garbage) fails here.
 */
abstract class ActivePathProbesTestSuite {
    internal abstract fun simEnv(): MigrationSimEnv

    @Test
    fun theActivePathAndItsExpiriesReadAsQuicheHoldsThem() =
        runTest {
            val env = simEnv()
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished(ONE_WAY)
                pair.exchange(ONE_WAY) { true }
                pair.issueSpareCids(pair.server)
                pair.issueSpareCids(pair.client)
                pair.exchange(ONE_WAY) { true }
                assertIs<ProbeOutcome.Probed>(pair.probe(MOVED_TO))
                pair.exchange(ONE_WAY) { true }
                assertIs<MigrateOutcome.Migrated>(pair.migrate(MOVED_TO))
                pair.exchange(ONE_WAY) { true }
                // Data the network loses: the client's PTOs on its new path go unanswered.
                assertTrue(pair.streamSend(pair.client, QuicStreamId(0L), "lost", fin = false) > 0)
                repeat(LOST_STEPS) {
                    pair.sends(pair.client)
                    pair.sends(pair.server)
                    pair.elapse(STEP)
                }
                val active = activeOf(env.api, pair.client)
                assertTrue(active.first >= 1L, "the client moved off path 0: ${pair.pathTable(pair.client)}")
                assertTrue(active.second >= 1L, "the new path's PTOs went unanswered: ${pair.pathTable(pair.client)}")
                val packed = pair.onClock(pair.client) { env.api.connActivePathProbes(pair.client) }
                assertEquals(
                    activePathProbesOf(active.first, active.second),
                    packed,
                    "index ${packed ushr ACTIVE_PATH_INDEX_SHIFT}, expiries ${packed and ACTIVE_PATH_PROBES_MASK}; quiche holds $active",
                )
            } finally {
                pair.close()
            }
        }

    /** The index and PTO count of the path quiche flags active, read through the allocating statistics. */
    private fun activeOf(
        api: QuicheApi,
        conn: QuicheConn,
    ): Pair<Long, Long> {
        var index = 0L
        while (true) {
            val stats = api.connPathStats(conn, index) ?: return -1L to -1L
            if (stats.active) return index to stats.totalPtoCount
            index++
        }
    }

    private companion object {
        const val MOVED_TO = 1
        const val LOST_STEPS = 40
        val ONE_WAY = 30.milliseconds
        val STEP = 0.1.seconds
    }
}
