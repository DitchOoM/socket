package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.MigrationSimEnv
import com.ditchoom.socket.quic.replay.fixtures.interopQuicGoHandshakeLossPeerGaveUp
import com.ditchoom.socket.quic.replay.fixtures.interopS2nQuicHandshakeCorruptionStrandedRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Captured connections replayed against a fresh quiche pair ([replayCapture]). Each schedule under
 * `fixtures/` is `CaptureToSchedule`'s output for one capture.
 */
abstract class CaptureReplayTestSuite {
    internal abstract fun simEnv(): MigrationSimEnv

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /**
     * A client's request reaches the server with the first Handshake probe the network lets through.
     *
     * The capture: the client's Finished and its request are sent together and the Finished is lost. The
     * server discards the request, which it cannot read before the Finished. The client's next probe of
     * the Finished gets through 3.1 s later, the server completes its handshake, and the request is not
     * sent again until two further timeouts have passed and lost theirs: the connection idles out.
     *
     * RFC 9002 §6.2.4 has a probe carry the data in flight in the other packet number spaces as well,
     * and §6.2.1 restarts the timeout backoff when the Handshake keys are discarded.
     */
    @Test
    fun aRequestStrandedBehindALostFinishedArrivesWithTheProbeThatGetsThrough() =
        runTest {
            wrapTestBody {
                val schedule = interopS2nQuicHandshakeCorruptionStrandedRequest
                val report = replayCapture(simEnv(), schedule, Beyond.HealthyPath(oneWay = 15.milliseconds, runFor = 10.seconds))
                // The scenario is everything up to the probe of the Finished that gets through.
                assertTrue(report.followedCaptureUntil(FINISHED_PROBE_SENT - 100.milliseconds), report.describe())
                assertIs<Moment.At>(report.established, report.describe())
                assertTrue(
                    report.peerReadToTheEndBy(stream = 0, at = FINISHED_PROBE_ARRIVED + 50.milliseconds),
                    "the server must read the request when the probe of the Finished reaches it, at $FINISHED_PROBE_ARRIVED\n" +
                        report.describe(),
                )
            }
        }

    /**
     * A handshake that quic-go abandons after its five seconds fails for that reason, with our client
     * probing as RFC 9002 has it.
     *
     * The capture: the network lost the client's first Initial and every answer to its second but one,
     * then both probes of its next timeout. Its third timeout's probes got through, 6 s after quic-go
     * first heard from it, to a server that had dropped the connection, and the client idled out.
     *
     * The one answer that arrived was quic-go's retransmitted ServerHello, which carried no
     * acknowledgement. quiche bundles one, so the modelled peer cannot stand in for it and the replay
     * loses that datagram instead: nothing the client sends before quic-go's deadline differs for it.
     */
    @Test
    fun aHandshakeAbandonedAfterFiveSecondsFailsForThatAndNotForOurProbes() =
        runTest {
            wrapTestBody {
                val capture = interopQuicGoHandshakeLossPeerGaveUp.withPeerDatagramLost(RETRANSMITTED_SERVER_HELLO)

                val whole = replayCapture(simEnv(), capture)
                assertTrue(whole.followedCaptureUntil(PROBE_AFTER_THE_DEADLINE + 8.seconds), whole.describe())
                // RFC 9002 §6.2.1: each timeout is twice the last while nothing is acknowledged.
                val probes =
                    whole.sent
                        .map { it.at }
                        .distinct()
                        .take(5)
                val waits = probes.zipWithNext { earlier, later -> later - earlier }
                assertEquals(4, waits.size, whole.describe())
                waits.zipWithNext { shorter, longer ->
                    assertTrue(
                        (longer - shorter * 2).absoluteValue <= 10.milliseconds,
                        "a wait of $shorter is followed by one of $longer\n${whole.describe()}",
                    )
                }

                val untilThatProbe = capture.cutAt(PROBE_AFTER_THE_DEADLINE - 100.milliseconds)
                val healed =
                    Beyond.HealthyPath(
                        oneWay = 15.milliseconds,
                        runFor = 40.seconds,
                        peer = PeerPatience.HandshakeWithin(5.seconds),
                    )
                val abandoned = replayCapture(simEnv(), untilThatProbe, healed)
                assertEquals(Course.FollowedCapture, abandoned.course, abandoned.describe())
                assertEquals(Moment.Never, abandoned.established, abandoned.describe())
                // RFC 9000 §10.1: with nothing received, the connection ends silently at its idle timeout.
                assertEquals(ConnectionEnd.IdleTimeout(capture.ourTransport.idleTimeout), abandoned.end, abandoned.describe())

                val kept = replayCapture(simEnv(), untilThatProbe, healed.copy(peer = PeerPatience.Unbounded))
                assertEquals(Course.FollowedCapture, kept.course, kept.describe())
                val established = assertIs<Moment.At>(kept.established, "a peer that keeps the handshake completes it\n${kept.describe()}")
                assertTrue(established.at <= PROBE_AFTER_THE_DEADLINE + 100.milliseconds, kept.describe())
            }
        }

    private companion object {
        /** In [interopS2nQuicHandshakeCorruptionStrandedRequest]: the client's probe of its Finished, which the network delivered. */
        val FINISHED_PROBE_SENT = 4_144_384.microseconds
        val FINISHED_PROBE_ARRIVED = 4_159_743.microseconds

        /**
         * In [interopQuicGoHandshakeLossPeerGaveUp]: the peer's second datagram, and the client's first
         * probe that quic-go no longer answered.
         */
        const val RETRANSMITTED_SERVER_HELLO = 1
        val PROBE_AFTER_THE_DEADLINE = 7_000_746.microseconds
    }
}
