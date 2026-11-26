package com.ditchoom.socket.quic.core.patch

import com.ditchoom.socket.quic.DEFAULT_PATH_LATENCY
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.core.edge.Ending
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guards for the quiche patches that fix handshake, PTO and early-packet behaviour. Each test stages one
 * wire order on a real quiche pair with a virtual clock; it goes red when the patch it names is reverted alone.
 */
class QuichePatchHandshakeTests {
    private val env = jvmMigrationSimEnv()

    /**
     * A client's handshake is confirmed when the server's HANDSHAKE_DONE arrives, one round trip after it
     * completes; a server's as it completes (RFC 9001 §4.1.2). The read the migration gate depends on.
     */
    @Test
    fun aClientIsConfirmedOnlyWhenHandshakeDoneArrives() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                assertTrue(pair.established(pair.client))
                assertFalse(pair.confirmed(pair.client), "a client that has only sent its Finished is not confirmed")
                val finished = pair.sends(pair.client)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(finished, pair.server)
                assertTrue(pair.confirmed(pair.server), "a server is confirmed as its handshake completes")
                assertFalse(pair.confirmed(pair.client), "the client is not confirmed before HANDSHAKE_DONE arrives")
                val handshakeDone = pair.sends(pair.server)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(handshakeDone, pair.client)
                assertTrue(pair.confirmed(pair.client), "HANDSHAKE_DONE confirms the client")
            } finally {
                pair.close()
            }
        }

    /**
     * Both probes of a PTO carry a lost ClientHello, not just the first.
     *
     * quic-interop-runner `handshakeloss` / `handshakecorruption` with our client against msquic (runs
     * 42300097213, 42307875620, 42317450511, 42319967477): quiche answers its second PTO with two probes
     * but put the ClientHello only in the first and a bare PING in the second. When the first was lost
     * and the PING arrived, msquic acknowledged it in an Initial from one connection ID and answered the
     * ClientHello, when it came, from another. The client had switched to the first (RFC 9000 §7.2), so
     * the server's `initial_source_connection_id` named the wrong one and the client closed with
     * TRANSPORT_PARAMETER_ERROR. When that acknowledgement was lost instead, the connection survived but
     * the runner counted two server connection IDs as two handshakes ("Expected 50 handshakes. Got: 51").
     */
    @Test
    fun bothProbesOfAPtoCarryALostClientHello() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                // The ClientHello is lost, and so is the one probe of the first PTO.
                assertTrue(pair.sends(pair.client).isNotEmpty())
                pair.elapse(pair.nextTimer(pair.client)!!)
                assertEquals(1, pair.sends(pair.client).size, "the first PTO sends one probe")
                // The second PTO sends two, and only the second arrives.
                pair.elapse(pair.nextTimer(pair.client)!!)
                val probes = pair.sends(pair.client)
                assertEquals(2, probes.size, "the second PTO sends two probes")
                pair.deliver(listOf(probes.last()), pair.server)

                val answer = pair.sends(pair.server)
                assertTrue(
                    answer.any { "Handshake" in it.packetTypes },
                    "the server must answer the second probe with its handshake flight, which it can only do " +
                        "if the probe carried the ClientHello; it answered ${answer.map { it.packetTypes }}",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * A client whose Finished is lost sends it again at every PTO until the handshake is confirmed, rather
     * than spending every other PTO on 1-RTT data the server cannot use yet.
     *
     * RFC 9002 §6.2.1: an endpoint MUST NOT arm the PTO for the Application Data space until the handshake
     * is confirmed. quiche armed it once the handshake was complete, which a client is the moment it sends
     * its Finished, so with a request in flight its PTOs alternated: Finished, request, Finished, request,
     * each a doubling of the last. The quic-interop-runner `handshakeloss` with our client against quic-go
     * (runs 42296178366, 42311415265, 42317450511, 42319967477): the Finished went out again 1, 5 and 21 s
     * after it was lost; quic-go abandons a handshake that has not completed in 10 s, so the one copy
     * that arrived at 24 s found no connection, and the client gave up on its idle timeout at 30 s.
     */
    @Test
    fun aLostFinishedIsSentAgainAtEveryPtoUntilTheHandshakeIsConfirmed() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                // The Finished is lost, and so is the request behind it.
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                val finished = pair.sends(pair.client)
                assertTrue(finished.any { "Handshake" in it.packetTypes }, "the client sends its Finished")

                repeat(LOST_FINISHED_PTOS) { pto ->
                    pair.elapse(pair.nextTimer(pair.client)!!)
                    // Beside the probes, the request leaves again in a 1-RTT datagram of its own.
                    val probes = pair.sends(pair.client).filterNot { it.packetTypes == listOf("1-RTT") }
                    assertTrue(
                        probes.isNotEmpty() && probes.all { "Handshake" in it.packetTypes },
                        "PTO ${pto + 1} must probe with the Finished (Handshake packets) while the handshake is " +
                            "unconfirmed; it sent ${probes.map { it.packetTypes }}",
                    )
                }
            } finally {
                pair.close()
            }
        }

    /**
     * An ack-eliciting 1-RTT packet that carries no data does not arm the client's Application Data PTO while its
     * handshake is unconfirmed, so the lost Finished is still re-sent at every PTO.
     *
     * RFC 9002 §6.2.1: an endpoint MUST NOT arm the PTO for the Application Data space until the handshake is
     * confirmed. A PING in flight is ack-eliciting but is not data, so the Handshake PTO probe does not re-send it,
     * and an Application Data timer armed for it (quiche armed it once the handshake was complete, which a client is
     * when it sends its Finished) fires before the Handshake space's second probe and spends that PTO on a probe the
     * server cannot read yet.
     *
     * Red against a quiche built without `patchQuichePtoWaitsForConfirmation`.
     */
    @Test
    fun aPingInFlightDoesNotTakeAPtoFromTheLostFinished() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                val finished = pair.sends(pair.client)
                assertTrue(finished.any { "Handshake" in it.packetTypes }, "the client sends its Finished")
                pair.elapse(PING_LAG)
                env.api.setThreadVirtualTimeNanos(pair.nowNanos(pair.client))
                try {
                    assertEquals(0, env.api.connSendAckEliciting(pair.client))
                } finally {
                    env.api.clearThreadVirtualTime()
                }
                assertTrue(pair.sends(pair.client).isNotEmpty(), "the client sends its PING")

                repeat(LOST_FINISHED_PTOS) { pto ->
                    pair.elapse(pair.nextTimer(pair.client)!!)
                    val probes = pair.sends(pair.client)
                    assertTrue(
                        probes.isNotEmpty() && probes.all { "Handshake" in it.packetTypes },
                        "PTO ${pto + 1} must probe with the Finished (Handshake packets) while the handshake is " +
                            "unconfirmed; it sent ${probes.map { it.packetTypes }}",
                    )
                }
            } finally {
                pair.close()
            }
        }

    /**
     * A client's PTO backoff starts again when HANDSHAKE_DONE makes it discard its Handshake keys, so a
     * request lost behind a lossy handshake is sent again one PTO later, not a backed-off multiple of it.
     *
     * RFC 9002 §6.2.1: when Initial or Handshake keys are discarded the PTO and loss detection timers MUST
     * be reset; Appendix A.10 sets `pto_count` to 0. quiche kept the count, so a client whose Finished took
     * two PTOs to arrive waited four PTOs to re-send its request. The quic-interop-runner `handshakeloss`
     * against s2n-quic and quinn: HANDSHAKE_DONE arrived after the Finished had been probed four and eight
     * times, the request's next copy went out 25 s later, and the read deadline or idle timeout fired first.
     */
    @Test
    fun theClientsPtoBackoffStartsAgainWhenItDiscardsItsHandshakeKeys() =
        runTest {
            val baseline = QuichePairByHand.open(env)
            val firstRequestPto =
                try {
                    baseline.handshakeUpToTheClientsFinished()
                    baseline.exchange(DEFAULT_PATH_LATENCY) { true }
                    assertTrue(baseline.confirmed(baseline.client), "the client confirms a handshake that lost nothing")
                    assertEquals(7, baseline.streamSend(baseline.client, QuicStreamId(0), "request", fin = true))
                    assertTrue(baseline.sends(baseline.client).isNotEmpty(), "the client sends its request")
                    baseline.nextTimer(baseline.client)!!
                } finally {
                    baseline.close()
                }

            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                // The Finished, the request and the first PTO's probe are lost.
                assertTrue(pair.sends(pair.client).isNotEmpty(), "the client sends its Finished and request")
                pair.elapse(pair.nextTimer(pair.client)!!)
                assertTrue(pair.sends(pair.client).isNotEmpty(), "the first PTO probes")
                // Of the second PTO's probes only one Handshake packet, the Finished, arrives.
                pair.elapse(pair.nextTimer(pair.client)!!)
                val probe = pair.sends(pair.client).last { "Handshake" in it.packetTypes }
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(listOf(QuichePairByHand.Datagram(probe.packet("Handshake"), probe.clientEndpoint)), pair.server)
                assertTrue(pair.confirmed(pair.server), "the server confirms its handshake on the Finished")
                // Only the server's 1-RTT packet, HANDSHAKE_DONE, arrives; its Handshake acknowledgement is lost.
                val done = pair.sends(pair.server).filter { "1-RTT" in it.packetTypes }
                assertTrue(done.isNotEmpty(), "the server sends HANDSHAKE_DONE")
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(done.map { QuichePairByHand.Datagram(it.packet("1-RTT"), it.clientEndpoint) }, pair.client)
                assertTrue(pair.confirmed(pair.client), "HANDSHAKE_DONE confirms the client's handshake")
                // The client writes again, so its PTO is measured from now, as the baseline's is.
                assertEquals(4, pair.streamSend(pair.client, QuicStreamId(4), "more", fin = true))
                assertTrue(pair.sends(pair.client).isNotEmpty(), "the client sends its second request")

                val due = pair.nextTimer(pair.client)!!
                assertTrue(
                    due <= firstRequestPto * 3 / 2,
                    "the PTO must start from no backoff once the Handshake keys are discarded: it is due in $due, a " +
                        "handshake that lost nothing re-sends its request in $firstRequestPto",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * A client whose Finished reached the server, but whose request and the server's HANDSHAKE_DONE were
     * lost, sends the request again with its next Handshake probe, in a datagram of its own, which the
     * server can read.
     *
     * RFC 9002 §6.2.4: besides the space whose timer expired, a sender SHOULD send ack-eliciting packets
     * in the other spaces with data in flight, coalescing them, "when the client has both Handshake and
     * Application Data in flight because the peer might only have receive keys for one of the two". A
     * server discards its Handshake keys once its handshake completes, so it ignores the client's
     * Handshake probes, and an unconfirmed client arms no Application Data PTO (§6.2.1): quiche re-sent
     * only the Finished until HANDSHAKE_DONE arrived, each probe backed off further. The quic-interop-runner
     * `handshakeloss` against s2n-quic, quinn and rama ended on the client's idle timeout or read deadline
     * this way. The copy may not ride behind the Handshake packet: a receiver may drop the rest of a datagram
     * once one packet in it cannot be decrypted, and a confirmed server cannot decrypt the Handshake probe
     * (stock quiche does exactly that).
     */
    @Test
    fun aHandshakeProbeCarriesTheRequestTheConfirmedServerNeverGot() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                val first = pair.sends(pair.client)
                // Only the Finished arrives; the request riding beside it is lost.
                val finished = first.filter { "Handshake" in it.packetTypes }
                assertTrue(finished.isNotEmpty(), "the client sends its Finished")
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(finished.map { QuichePairByHand.Datagram(it.packet("Handshake"), it.clientEndpoint) }, pair.server)
                assertTrue(pair.confirmed(pair.server), "the server completes and confirms its handshake on the Finished")
                // HANDSHAKE_DONE and the server's acknowledgements are lost.
                assertTrue(pair.sends(pair.server).isNotEmpty(), "the server sends HANDSHAKE_DONE")

                pair.elapse(pair.nextTimer(pair.client)!!)
                val probes = pair.sends(pair.client)
                val ownDatagrams = probes.filter { it.packetTypes == listOf("1-RTT") }
                assertTrue(
                    probes.any { "Handshake" in it.packetTypes } && ownDatagrams.isNotEmpty(),
                    "the Handshake probe must be followed by the unacknowledged request in a 1-RTT datagram of " +
                        "its own; it sent ${probes.map { it.packetTypes }}",
                )
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(ownDatagrams, pair.server)
                assertEquals("request" to true, pair.readText(pair.server, QuicStreamId(0)), "the server reads the request")
            } finally {
                pair.close()
            }
        }

    /**
     * Both probes of a PTO carry a lost Finished, so the server completes its handshake when only the
     * second arrives. Before, the second was a bare PING: the same loss pattern that strands a ClientHello
     * ([bothProbesOfAPtoCarryALostClientHello]) one flight later.
     */
    @Test
    fun bothProbesOfAPtoCarryALostFinished() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                // The Finished is lost, and so is the one probe of the first PTO. Beside the probes, the request
                // leaves again in a 1-RTT datagram of its own; only the probes are counted.
                assertTrue(pair.sends(pair.client).isNotEmpty())
                pair.elapse(pair.nextTimer(pair.client)!!)
                assertEquals(1, pair.sends(pair.client).count { "Handshake" in it.packetTypes }, "the first PTO sends one probe")
                // The second PTO sends two, and only the second arrives.
                pair.elapse(pair.nextTimer(pair.client)!!)
                val probes = pair.sends(pair.client).filter { "Handshake" in it.packetTypes }
                assertEquals(2, probes.size, "the second PTO sends two probes")
                pair.deliver(listOf(probes.last()), pair.server)

                assertTrue(
                    pair.established(pair.server),
                    "the server must complete its handshake on the second probe alone, which it can only do " +
                        "if the probe carried the Finished; the probes were ${probes.map { it.packetTypes }}",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * A server whose first flight is lost sends it again as soon as the client's ClientHello arrives a
     * second time, not on its own PTO a second or more later.
     *
     * RFC 9002 §6.2.3: a server that receives an Initial packet carrying CRYPTO data it already has can
     * assume the client did not get its Initial, and MAY re-send its unacknowledged CRYPTO data before
     * the PTO. quiche answered such a packet with a bare ACK. The quic-interop-runner `handshakeloss` /
     * `handshakecorruption` with our server against quic-go (runs 42324644154, 42326957428, 42329439382,
     * 42331807229): the ServerHello was lost, quic-go re-sent its ClientHello at 106 ms and then a PING-only
     * Initial at 0.3, 0.7, 1.3, 2 and 4 s, each answered by an ACK alone; the server re-sent the ServerHello
     * only on its PTOs, and quic-go abandoned the handshake at 10 s.
     */
    @Test
    fun aRepeatedClientHelloBringsTheServersLostFlightAgainAtOnce() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val hello = pair.sends(pair.client)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(hello, pair.server)
                // The server's whole first flight is lost.
                val flight = pair.sends(pair.server)
                assertTrue(flight.any { "Handshake" in it.packetTypes }, "the server answers the ClientHello with its flight")

                // The client's PTO sends its ClientHello again, and it arrives before the server's own PTO.
                pair.elapse(pair.nextTimer(pair.client)!!)
                val again = pair.sends(pair.client)
                assertTrue(again.isNotEmpty(), "the client's PTO re-sends its ClientHello")
                assertTrue(pair.nextTimer(pair.server)!!.isPositive(), "the server's own PTO has not fired yet")
                pair.deliver(again, pair.server)

                val answer = pair.sends(pair.server)
                assertTrue(
                    answer.any { "Initial" in it.packetTypes } && answer.any { "Handshake" in it.packetTypes },
                    "the server must answer a repeated ClientHello with its lost flight, Initial and Handshake; " +
                        "it answered ${answer.map { it.packetTypes }}",
                )
                pair.deliver(answer, pair.client)
                assertTrue(pair.established(pair.client), "the re-sent flight alone completes the client's handshake")
            } finally {
                pair.close()
            }
        }

    /**
     * A server's PTO re-sends its whole lost flight — the ServerHello in an Initial packet coalesced with
     * the Handshake packets behind it — not one packet number space at a time.
     *
     * RFC 9002 §6.2.4: besides the space whose timer expired, a sender SHOULD send ack-eliciting packets in
     * the other spaces with data in flight, coalescing them. quiche probed one space per PTO. In the
     * quic-interop-runner trace of [aRepeatedClientHelloBringsTheServersLostFlightAgainAtOnce] the server's
     * first PTO re-sent only the ServerHello (lost again), and its second only the Handshake packets: both
     * reached quic-go, which could not decrypt them without the ServerHello.
     */
    @Test
    fun aServerPtoResendsTheWholeLostFlightNotOneSpaceOfIt() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val hello = pair.sends(pair.client)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(hello, pair.server)
                // The server's first flight is lost.
                assertTrue(pair.sends(pair.server).any { "Handshake" in it.packetTypes })

                pair.elapse(pair.nextTimer(pair.server)!!)
                val probes = pair.sends(pair.server)
                assertTrue(
                    probes.any { "Initial" in it.packetTypes && "Handshake" in it.packetTypes },
                    "the server's PTO must send the ServerHello and the Handshake packets behind it, coalesced; " +
                        "it sent ${probes.map { it.packetTypes }}",
                )
                pair.deliver(probes, pair.client)
                assertTrue(pair.established(pair.client), "the PTO's datagrams alone complete the client's handshake")
            } finally {
                pair.close()
            }
        }

    /**
     * A client keeps the server's Handshake packets that arrive before the ServerHello and reads them once
     * it has the keys, so losing only the datagram that carries the ServerHello costs the server one
     * datagram sent again, not its Handshake flight.
     *
     * RFC 9000 §5.2.1 and RFC 9001 §4.1.4 let an endpoint buffer packets it has no keys for yet. quiche
     * dropped a client's. In the quic-interop-runner the server's whole Handshake flight reached our client
     * intact 1.1 s before the ServerHello and was dropped, and the handshake failed.
     */
    @Test
    fun aClientReadsTheHandshakePacketsThatArrivedBeforeTheServerHello() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val flight = pair.serverFirstFlight()
                // Only the datagram that carries the ServerHello is lost.
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(flight.drop(1), pair.client)
                assertFalse(pair.established(pair.client), "the client cannot complete without the ServerHello")

                val resent = pair.serverDatagramsUntilTheClientCompletes()
                assertEquals(
                    1,
                    resent.size,
                    "the client must complete on the one datagram that brings the ServerHello again, reading the " +
                        "Handshake packets it kept; the server had to send ${resent.shape()}",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * Junk Handshake packets that arrive after the server's genuine ones, before the ServerHello, are
     * dropped: the genuine ones are kept and read, and the junk changes nothing.
     */
    @Test
    fun junkHandshakePacketsBehindTheGenuineOnesAreDroppedAndTheGenuineOnesRead() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val flight = pair.serverFirstFlight()
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(flight.drop(1), pair.client)
                pair.deliver(junkHandshakeDatagrams(flight.last()), pair.client)

                val resent = pair.serverDatagramsUntilTheClientCompletes()
                assertEquals(
                    1,
                    resent.size,
                    "the junk must not displace the genuine Handshake packets kept before it; the server had to " +
                        "send ${resent.shape()}",
                )
                assertEquals("open", pair.closed(pair.client), "junk the client cannot decrypt must not close it")
            } finally {
                pair.close()
            }
        }

    /**
     * A client keeps only the first few Handshake packets that arrive before the ServerHello: a flood of
     * junk fills the buffer, the genuine packets behind it are dropped as quiche always did, and the
     * handshake still completes once the server sends them again.
     */
    @Test
    fun aFloodOfJunkHandshakePacketsBeforeTheServerHelloIsCappedAndTheHandshakeStillCompletes() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val flight = pair.serverFirstFlight()
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(junkHandshakeDatagrams(flight.last()), pair.client)
                pair.deliver(flight.drop(1), pair.client)

                val resent = pair.serverDatagramsUntilTheClientCompletes()
                assertTrue(
                    resent.size > 1,
                    "a client whose buffer junk filled first must not also keep the genuine packets behind it " +
                        "(the buffer is bounded, first come first kept); it completed on ${resent.shape()}",
                )
                assertEquals("open", pair.closed(pair.client), "junk the client cannot decrypt must not close it")
            } finally {
                pair.close()
            }
        }

    /**
     * Every PTO probe of a server carries a HANDSHAKE_DONE the client has not acknowledged, so whichever
     * probe reaches the client confirms its handshake. Before, only a probe that re-sent the packet
     * HANDSHAKE_DONE first rode in did, and only when that packet also carried CRYPTO or STREAM data:
     * quiche's probes re-queue the frames of such packets alone, and otherwise send a PING.
     *
     * The quic-interop-runner `handshakeloss` with our server against quic-go (run 42345499750): quic-go
     * takes no session ticket, so HANDSHAKE_DONE went out in a packet of its own, and it was lost. The
     * server probed with PINGs at 1, 3, 7 and 15 s, and quic-go's acknowledgements of them — which would
     * have declared HANDSHAKE_DONE lost — were lost as well. Unconfirmed, quic-go never armed its
     * Application Data PTO (RFC 9002 §6.2.1), so it never re-sent the request the server had dropped
     * before its handshake completed, and the connection idled out. Here the server issues a ticket, so
     * the first probe of each PTO re-sends HANDSHAKE_DONE with it; the second probe is the one that was
     * a PING.
     */
    @Test
    fun everyServerPtoProbeCarriesAnUnacknowledgedHandshakeDone() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                val finished = pair.sends(pair.client)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(finished, pair.server)
                assertTrue(pair.established(pair.server), "the server completes its handshake on the Finished")
                // HANDSHAKE_DONE is lost, and so is the one probe of the first PTO.
                assertTrue(pair.sends(pair.server).isNotEmpty(), "the server sends HANDSHAKE_DONE")
                pair.elapse(pair.nextTimer(pair.server)!!)
                assertEquals(1, pair.sends(pair.server).size, "the first PTO sends one probe")
                // The second PTO sends two, and only the second arrives; the client's answer is lost.
                pair.elapse(pair.nextTimer(pair.server)!!)
                val probes = pair.sends(pair.server)
                assertEquals(2, probes.size, "the second PTO sends two probes")
                pair.deliver(listOf(probes.last()), pair.client)
                pair.sends(pair.client)

                // The client writes more, and that is lost too. Only a confirmed client spends its next PTO
                // on it (RFC 9002 §6.2.1); an unconfirmed one re-sends its Finished in a Handshake packet,
                // which a confirmed server can no longer read.
                assertEquals(4, pair.streamSend(pair.client, QuicStreamId(4), "more", fin = true))
                assertTrue(pair.sends(pair.client).isNotEmpty(), "the client sends its second request")
                val due = pair.nextTimer(pair.client)
                assertTrue(due != null && due < CLIENT_PTO_CEILING, "the client arms a PTO; its next timer was $due")
                pair.elapse(due)
                val probe = pair.sends(pair.client)
                assertTrue(
                    probe.any { "1-RTT" in it.packetTypes } && probe.none { "Handshake" in it.packetTypes },
                    "the server's second probe must carry HANDSHAKE_DONE, so the confirmed client's PTO re-sends its " +
                        "lost request; it sent ${probe.map { it.packetTypes }}",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * A request that reaches the server before the client's Finished is served once the Finished arrives,
     * without the client sending it again.
     *
     * A server cannot read 1-RTT data before its handshake completes (RFC 9001 §5.7, which lets it buffer
     * the packets), and quiche dropped it. The quic-interop-runner `handshakecorruption` with our server
     * against quinn (runs 42326957428, 42363016612): the Finished was lost, the request beside it reached
     * the server and was dropped, and quinn's next PTO after the handshake completed fell after its idle
     * timeout.
     */
    @Test
    fun aRequestThatArrivesBeforeTheFinishedIsServedOnceItDoes() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                val finished = pair.sends(pair.client)
                assertTrue(finished.any { "Handshake" in it.packetTypes }, "the client sends its Finished")
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                val request = pair.sends(pair.client)
                assertTrue(
                    request.isNotEmpty() && request.all { it.packetTypes == listOf("1-RTT") },
                    "the request leaves in 1-RTT packets of its own, was ${request.map { it.packetTypes }}",
                )

                // The request overtakes the Finished.
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(request, pair.server)
                assertFalse(pair.established(pair.server), "the server has not completed its handshake yet")
                pair.deliver(finished, pair.server)
                assertTrue(pair.established(pair.server), "the Finished completes the server's handshake")

                assertEquals(
                    7 to true,
                    pair.readAll(pair.server, QuicStreamId(0)),
                    "the server must serve the request that arrived before its handshake completed",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * A 1-RTT packet coalesced behind a Handshake packet the server can no longer decrypt is still read.
     *
     * RFC 9000 §12.2: a receiver that cannot process one coalesced packet still processes the ones after
     * it, and a long header's Length field says where the next one starts. quiche stopped at the first
     * packet it could not decrypt and dropped the rest of the datagram. Here the client's own Finished
     * packet, replayed once the server has confirmed the handshake and discarded its Handshake keys, sits
     * in front of a request.
     */
    @Test
    fun aOneRttPacketBehindAnUndecryptableHandshakePacketIsStillRead() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val handshakePacket = pair.confirmHandshakeKeepingTheClientsFinished()
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                val request = pair.sends(pair.client).single()
                assertEquals(listOf("1-RTT"), request.packetTypes)

                pair.deliver(listOf(QuichePairByHand.Datagram(handshakePacket + request.bytes, request.clientEndpoint)), pair.server)

                assertEquals(
                    "request" to true,
                    pair.readText(pair.server, QuicStreamId(0)),
                    "the server must read the 1-RTT packet that follows the Handshake packet it cannot decrypt",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * A CONNECTION_CLOSE in a 1-RTT packet coalesced behind an undecryptable Handshake packet closes the
     * connection.
     *
     * The quic-interop-runner case, with quinn as the client: quinn, not yet sure the handshake was
     * confirmed, sent its CONNECTION_CLOSE in a Handshake and a 1-RTT packet in one datagram (RFC 9000
     * §10.2.3). Our server had confirmed and discarded its Handshake keys, so it could not decrypt the
     * first, dropped the second with it, never saw the close, and retransmitted until its idle timeout.
     */
    @Test
    fun aCloseBehindAnUndecryptableHandshakePacketClosesTheConnection() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val handshakePacket = pair.confirmHandshakeKeepingTheClientsFinished()
                assertEquals(0, pair.closeWith(pair.client, QuicError.ApplicationError(CLOSE_CODE)))
                val close = pair.sends(pair.client).single()
                assertEquals(listOf("1-RTT"), close.packetTypes)

                pair.deliver(listOf(QuichePairByHand.Datagram(handshakePacket + close.bytes, close.clientEndpoint)), pair.server)

                val ended = pair.closed(pair.server)
                assertTrue(
                    "peer=" in ended && "peer=null" !in ended,
                    "the server must see the client's CONNECTION_CLOSE behind the Handshake packet it cannot " +
                        "decrypt; it is $ended",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * The client sends its ClientHello and the server answers it; returns the server's first flight, whose
     * first datagram alone carries the ServerHello and whose Handshake packets span at least two datagrams.
     */
    private fun QuichePairByHand.serverFirstFlight(): List<QuichePairByHand.Datagram> {
        val hello = sends(client)
        elapse(DEFAULT_PATH_LATENCY)
        deliver(hello, server)
        val flight = sends(server)
        assertTrue(
            flight.size >= 2 &&
                "Initial" in flight.first().packetTypes &&
                flight.drop(1).all { it.packetTypes == listOf("Handshake") },
            "the server's flight must be the ServerHello's datagram then Handshake-only ones; it is ${flight.shape()}",
        )
        return flight
    }

    /**
     * Run timers and carry every datagram, client to server whole and server to client one at a time, until
     * the client's handshake completes; returns the server datagrams the client received on the way.
     */
    private fun QuichePairByHand.serverDatagramsUntilTheClientCompletes(): List<QuichePairByHand.Datagram> {
        val received = mutableListOf<QuichePairByHand.Datagram>()
        repeat(HANDSHAKE_ROUNDS) {
            val toServer = sends(client)
            if (toServer.isNotEmpty()) {
                elapse(DEFAULT_PATH_LATENCY)
                deliver(toServer, server)
            }
            val toClient = sends(server)
            if (toClient.isNotEmpty()) elapse(DEFAULT_PATH_LATENCY)
            for (datagram in toClient) {
                deliver(listOf(datagram), client)
                received += datagram
                if (established(client)) return received
            }
            if (toServer.isEmpty() && toClient.isEmpty()) {
                elapse(listOfNotNull(nextTimer(client), nextTimer(server)).minOrNull() ?: error("no timer is armed"))
            }
        }
        error("the client had not completed its handshake after $HANDSHAKE_ROUNDS rounds; it received ${received.shape()}")
    }

    private fun junkHandshakeDatagrams(genuine: QuichePairByHand.Datagram): List<QuichePairByHand.Datagram> =
        List(JUNK_HANDSHAKE_PACKETS) { i ->
            val bytes = genuine.packet("Handshake")
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor (i + 1)).toByte()
            QuichePairByHand.Datagram(bytes, genuine.clientEndpoint)
        }

    private fun List<QuichePairByHand.Datagram>.shape(): String =
        "$size datagrams of ${sumOf { it.bytes.size }} bytes ${map { it.packetTypes }}"

    /**
     * Run the handshake to confirmation on both sides, losing nothing, and return the Handshake packet
     * that carried the client's Finished: a real packet the server can no longer decrypt.
     */
    private fun QuichePairByHand.confirmHandshakeKeepingTheClientsFinished(): ByteArray {
        handshakeUpToTheClientsFinished()
        val finished = sends(client)
        val handshakePacket = finished.first { "Handshake" in it.packetTypes }.packet("Handshake")
        deliver(finished, server)
        settle()
        assertTrue(established(client) && established(server), "the handshake must complete")
        return handshakePacket
    }

    /**
     * A client that closes the moment its handshake completes, before the server's HANDSHAKE_DONE has reached it,
     * still ends the server's connection.
     *
     * The server confirmed the handshake when the client's Finished arrived and discarded its Handshake keys then
     * (RFC 9001 §4.9.2), so a CONNECTION_CLOSE carried only in a Handshake packet is one it can no longer read. The
     * datagrams the close leaves in must hold a copy the server reads: the close the client sent is the only one, so
     * without it the server's connection lives until its idle timeout.
     *
     * Red against a quiche built without `patchQuicheCloseBeforeConfirmationAlsoInOneRtt`.
     */
    @Test
    fun aClientThatClosesBeforeItsHandshakeIsConfirmedStillClosesTheServer() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                pair.handshakeUpToTheClientsFinished()
                val finished = pair.sends(pair.client)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(finished, pair.server)
                assertTrue(pair.confirmed(pair.server), "the server confirms as the client's Finished arrives")
                assertFalse(pair.confirmed(pair.client), "the client has not seen HANDSHAKE_DONE, so it is not confirmed")
                assertEquals(0, pair.closeWith(pair.client, QuicError.NoError))

                val close = pair.sends(pair.client)
                assertTrue(close.isNotEmpty(), "the close must leave the client")
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(close, pair.server)
                assertEquals(
                    QuichePairByHand.Ending.ClosedByPeer,
                    pair.ending(pair.server),
                    "the server must end on the client's own close, not on its idle timer; the close datagrams were " +
                        "${close.map { it.packetTypes }}",
                )
            } finally {
                pair.close()
            }
        }
}

private val CLIENT_PTO_CEILING = 10.seconds

private const val CLOSE_CODE = 0x0L

private const val HANDSHAKE_ROUNDS = 32

private const val JUNK_HANDSHAKE_PACKETS = 64

private val PING_LAG = 50.milliseconds

private const val LOST_FINISHED_PTOS = 3
