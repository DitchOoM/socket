package com.ditchoom.socket.quic

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `quiche_conn_probe_path_with` on a real quiche pair: the probed path assumes the round trip it is given until it has
 * a sample, and its PATH_CHALLENGE leaves even when the challenges it sent before, never answered, fill its
 * congestion window, which holds a plain probe's.
 */
class ProbePathWithTests {
    private val env = jvmMigrationSimEnv()

    @Test
    fun aProbedPathAssumesTheRoundTripItIsGivenUntilItHasASample() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.establishWithSpareIds()
                assertIs<ProbeOutcome.Probed>(pair.probeWith(MOVED_TO, CELLULAR_PRIOR.inWholeNanoseconds))
                val probed = pair.unvalidatedPathRtts()
                assertEquals(listOf(CELLULAR_PRIOR), probed, "the probed path's estimate: ${pair.pathTable(pair.client)}")
            } finally {
                pair.close()
            }
        }

    @Test
    fun aChallengeLeavesPastAWindowFilledByUnansweredChallenges() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.establishWithSpareIds()
                // Challenges the network loses, faster than any loss timer: their bytes stay in flight on the path.
                repeat(FILLING_CHALLENGES) {
                    assertIs<ProbeOutcome.Probed>(pair.probeWith(MOVED_TO, CELLULAR_PRIOR.inWholeNanoseconds))
                    pair.sends(pair.client)
                    pair.elapse(STEP)
                }
                assertIs<ProbeOutcome.Probed>(pair.probe(MOVED_TO))
                val held = pair.sends(pair.client).count { it.clientEndpoint == MOVED_TO }
                assertEquals(0, held, "a plain probe's challenge is held by the full window")
                assertIs<ProbeOutcome.Probed>(pair.probeWith(MOVED_TO, CELLULAR_PRIOR.inWholeNanoseconds))
                val sent = pair.sends(pair.client).count { it.clientEndpoint == MOVED_TO }
                assertTrue(sent >= 1, "the challenge left past the window: ${pair.pathTable(pair.client)}")
            } finally {
                pair.close()
            }
        }

    private fun QuichePairByHand.establishWithSpareIds() {
        handshakeUpToTheClientsFinished(ONE_WAY)
        exchange(ONE_WAY) { true }
        issueSpareCids(server)
        issueSpareCids(client)
        exchange(ONE_WAY) { true }
        assertTrue(confirmed(client), "the client's handshake is confirmed")
    }

    /** The RTT estimate of each of the client's paths that is not validated. */
    private fun QuichePairByHand.unvalidatedPathRtts() =
        (0 until pathCount(client)).mapNotNull { idx ->
            env.api
                .connPathStats(client, idx)
                ?.takeIf { it.validationState != VALIDATED }
                ?.rtt
        }

    private companion object {
        const val MOVED_TO = 1
        const val VALIDATED = 3L
        const val FILLING_CHALLENGES = 20
        val ONE_WAY = 30.milliseconds
        val STEP = 5.milliseconds
        val CELLULAR_PRIOR = 150.milliseconds
    }
}
