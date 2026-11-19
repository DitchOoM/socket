package com.ditchoom.socket.quic

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A real quiche pair on a 60 ms round trip. quiche counts a PATH_CHALLENGE lost when a loss timer passes it, re-asks
 * validation for each one counted, and failed a path at the third count even when the path was validated and active,
 * leaving no path: the connection then closed with no error. A validated path is never failed by unanswered
 * challenges; a connection that does run out of paths closes with NO_VIABLE_PATH.
 */
class ValidatedPathProbeLossTests {
    private val env = jvmMigrationSimEnv()

    @Test
    fun aValidatedActivePathSurvivesChallengesHeldPastThreeLossTimers() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.establishWithSpareIds()
                assertIs<ProbeOutcome.Probed>(pair.probe(ORIGINAL), "the active path takes a probe: ${pair.pathTable(pair.client)}")
                // The uplink holds every datagram for seconds: each loss timer counts the challenge it passes.
                pair.hold(UPLINK_HELD) { it.clientEndpoint != ORIGINAL }
                pair.exchange(ONE_WAY) { true }
                assertEquals("open", pair.closed(pair.client), "the active path is validated: ${pair.pathTable(pair.client)}")
                assertEquals(SENT, pair.echoThrough(), "the path still carries the connection")
            } finally {
                pair.close()
            }
        }

    @Test
    fun aConnectionWithNoPathLeftClosesWithNoViablePath() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.establishWithSpareIds()
                assertIs<ProbeOutcome.Probed>(pair.probe(MOVED_TO), "a spare id is held for the probe")
                val moved = assertIs<MigrateOutcome.Migrated>(pair.migrate(MOVED_TO), "the probed path takes the connection")
                assertTrue(moved.dcidSeq > 0, "the move took a new id")
                assertEquals(0, pair.retireDcid(0L), "the original path's id is retired")
                // Nothing on the new path is ever answered: it fails its validation, and no path is left.
                pair.hold(NEVER_ANSWERED) { false }
                assertEquals(
                    "closed local=${QuicError.NoViablePath} peer=null",
                    pair.closed(pair.client),
                    "paths: ${pair.pathTable(pair.client)}",
                )
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

    /** For [span], every datagram either side sends is lost unless [carries] lets it through; timers fire on the way. */
    private fun QuichePairByHand.hold(
        span: Duration,
        carries: (QuichePairByHand.Datagram) -> Boolean,
    ) {
        val until = elapsed + span
        while (elapsed < until) {
            val toServer = sends(client)
            val toClient = sends(server)
            elapse(STEP)
            deliver(toServer.filter(carries), server)
            deliver(toClient.filter(carries), client)
        }
    }

    /** The client sends a request on a new stream and the server reads all of it: what the server read. */
    private fun QuichePairByHand.echoThrough(): Int {
        val stream = QuicStreamId(0)
        streamSend(client, stream, "x".repeat(SENT), fin = true)
        exchange(ONE_WAY) { true }
        return readAll(server, stream).first
    }

    private companion object {
        const val ORIGINAL = 0
        const val MOVED_TO = 1
        const val SENT = 100
        val ONE_WAY = 30.milliseconds
        val STEP = 10.milliseconds
        val UPLINK_HELD = 6.seconds
        val NEVER_ANSWERED = 20.seconds
    }
}
