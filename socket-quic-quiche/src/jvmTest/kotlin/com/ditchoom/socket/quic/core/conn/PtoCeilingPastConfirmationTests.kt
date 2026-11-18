package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.PtoCeiling
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A client [Handshake] on a real quiche pair and the pair's virtual clock, its timer expiries followed as the core's
 * connection follows them. The server's answer to the first flight is held 4 s while every client probe is lost, so
 * the only handshake RTT sample is 4 s: quiche's own PTO after confirmation is then about 12 s.
 */
class PtoCeilingPastConfirmationTests {
    private val ms = 1_000_000L
    private val env = jvmMigrationSimEnv()
    private val ceilings = ArrayList<PtoCeiling>()

    /** The real quiche, every ceiling the handshake sets recorded. */
    private val api: QuicheApi =
        object : QuicheApi by env.api {
            override fun connSetHandshakePtoCeiling(
                conn: QuicheConn,
                ceiling: PtoCeiling,
            ) {
                ceilings += ceiling
                env.api.connSetHandshakePtoCeiling(conn, ceiling)
            }
        }

    /** The adaptive rule of [link]'s prior: an unknown link (the interop client's) starts at 999 ms, Wi-Fi at 240 ms. */
    private fun ruleOf(link: LinkClass) = HandshakeRule.adaptive(HandshakeRule.priorOf(link), 25 * ms, HandshakeBound.UntilClosed, link)

    private fun firstCeilingOf(rule: HandshakeRule): Duration =
        when (val pto = rule.pto) {
            is PtoRule.Adaptive -> pto.firstCeilingNanos.nanoseconds
            is PtoRule.Rfc9002 -> Duration.INFINITE
        }

    private class Client(
        val pair: QuichePairByHand,
        val handshake: Handshake,
        val path: SinglePath,
    )

    /** What the core's connection does when the client's timer fires: [Rearm]'s `expired`. */
    private fun Client.followExpiry() =
        pair.onClock(pair.client) {
            when (handshake.confirmation()) {
                Confirmation.Pending -> handshake.expired(path.read())
                Confirmation.Confirmed -> Unit
            }
        }

    private fun Client.step() = pair.onClock(pair.client) { handshake.step(Nanos(pair.elapsed.inWholeNanoseconds)) }

    /** [span] passes; every timer that falls due fires, and whatever either side then sends is lost. */
    private fun Client.losing(span: Duration) =
        pair.elapse(span) { conn ->
            if (conn == pair.client) followExpiry()
            pair.sends(conn)
        }

    /** Rounds of every datagram carried both ways, [ONE_WAY] each leg, until neither side sends. */
    private fun Client.carryAll() {
        repeat(MAX_ROUNDS) {
            val toServer = pair.sends(pair.client)
            pair.elapse(ONE_WAY) { if (it == pair.client) followExpiry() }
            pair.deliver(toServer, pair.server)
            val toClient = pair.sends(pair.server)
            pair.elapse(ONE_WAY) { if (it == pair.client) followExpiry() }
            pair.deliver(toClient, pair.client)
            step()
            if (toServer.isEmpty() && toClient.isEmpty()) return
        }
        error("the pair was still exchanging datagrams after $MAX_ROUNDS rounds")
    }

    /** The client's handshake confirmed, its only RTT sample the 4 s the server's answer was held. */
    private suspend fun confirmedAfterAnInflatedSample(rule: HandshakeRule = ruleOf(LinkClass.Unknown)): Client {
        val pair = QuichePairByHand.open(env, configureClient = { env.api.configSetInitialRtt(it, rule.priorNanos()) })
        val q = QuicheConnRef(api, pair.client)
        val client =
            Client(
                pair,
                pair.onClock(pair.client) { Handshake(q, rule, 0L, nativeBytes(255)) },
                SinglePath(q, SockAddr(0L, 0), SockAddr(0L, 0), SendTarget.ConnectedPeer),
            )
        val first = pair.sends(pair.client)
        pair.elapse(ONE_WAY)
        pair.deliver(first, pair.server)
        val answer = pair.sends(pair.server)
        client.losing(HELD)
        pair.deliver(answer, pair.client)
        client.step()
        client.carryAll()
        assertEquals(Confirmation.Confirmed, client.handshake.confirmation(), "the client's handshake is confirmed")
        val rtt = assertNotNull(env.api.connPathStats(pair.client, 0L)).rtt
        assertTrue(rtt >= HELD, "the handshake's sample is inflated: $rtt")
        return client
    }

    /** The client's request on [stream] is lost; how long after it the client's first 1-RTT retransmission leaves. */
    private fun Client.firstRetransmissionOfALostRequest(stream: QuicStreamId = QuicStreamId(0)): Duration {
        assertEquals(7, pair.streamSend(pair.client, stream, "request", fin = true))
        val lostAt = pair.elapsed
        assertTrue(pair.sends(pair.client).any { "1-RTT" in it.packetTypes }, "the request left")
        var resent: Duration = Duration.INFINITE
        pair.elapse(WAIT, firing = listOf(pair.client)) {
            followExpiry()
            if (resent == Duration.INFINITE && pair.sends(pair.client).any { "1-RTT" in it.packetTypes }) {
                resent = pair.elapsed - lostAt
            }
        }
        return resent
    }

    /**
     * The interop handshakeloss failure: a request lost just after confirmation waited for quiche's PTO of an inflated
     * handshake sample (12 s there) while the peer gave up on it. Discarding the handshake keys starts the ceiling's
     * growth again from its first value, as it does quiche's backoff, and the ceiling still holds because no RTT sample
     * has been taken in the application space: the first 1-RTT retransmission leaves within the first ceiling. quiche
     * adds the peer's max_ack_delay to a PTO in the application space (RFC 9002 §6.2.1).
     */
    private suspend fun resentWithinTheFirstCeiling(link: LinkClass) {
        val rule = ruleOf(link)
        val client = confirmedAfterAnInflatedSample(rule)
        try {
            val first = firstCeilingOf(rule)
            val resent = client.firstRetransmissionOfALostRequest()
            assertTrue(
                resent <= first + MAX_ACK_DELAY,
                "$link: the first 1-RTT retransmission left $resent after the request, past the first ceiling of " +
                    "$first; ceilings set: ${ceilings.map(::shown)}",
            )
        } finally {
            client.pair.close()
        }
    }

    @Test
    fun aRequestLostAfterConfirmationIsResentWithinTheFirstCeilingOnAnUnknownLink() =
        runTest { resentWithinTheFirstCeiling(LinkClass.Unknown) }

    /** Wi-Fi's handshake sat through four expiries, which grew its ceiling past quiche's own PTO. */
    @Test
    fun aRequestLostAfterConfirmationIsResentWithinTheFirstCeilingOnWifi() = runTest { resentWithinTheFirstCeiling(LinkClass.Wifi) }

    /**
     * The server acknowledging a request is the first RTT sample in the application space: quiche releases the ceiling,
     * and a later loss waits for quiche's own PTO, of an estimate still mostly the handshake's.
     */
    @Test
    fun theFirstApplicationSampleReleasesTheCeiling() =
        runTest {
            val client = confirmedAfterAnInflatedSample()
            try {
                val pair = client.pair
                assertTrue(env.api.connHandshakePtoCeilingHeld(pair.client), "held past confirmation")
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                client.carryAll()
                assertEquals(false, env.api.connHandshakePtoCeilingHeld(pair.client), "released by the acknowledgement")
                val first = firstCeilingOf(ruleOf(LinkClass.Unknown))
                val resent = client.firstRetransmissionOfALostRequest(QuicStreamId(4))
                assertTrue(resent > first + MAX_ACK_DELAY, "quiche's own PTO, past the released ceiling: $resent")
                assertEquals(1, ceilings.size, "the core gave quiche its ceiling once: ${ceilings.map(::shown)}")
            } finally {
                client.pair.close()
            }
        }

    /**
     * The server holds its acknowledgement of the request as long as a hold may last, waiting for a reply that does not
     * come: the held ACK is still the first RTT sample in the application space and releases the ceiling.
     */
    @Test
    fun aHeldAcknowledgementStillReleasesTheCeiling() =
        runTest {
            val client = confirmedAfterAnInflatedSample()
            try {
                val pair = client.pair
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = true))
                val request = pair.sends(pair.client)
                pair.elapse(ONE_WAY) { if (it == pair.client) client.followExpiry() }
                pair.deliver(request, pair.server)
                pair.elapse(LONGEST_HOLD) { if (it == pair.client) client.followExpiry() }
                val held = pair.sends(pair.server)
                assertTrue(held.isNotEmpty(), "the server acknowledged the request at the end of its hold")
                pair.elapse(ONE_WAY) { if (it == pair.client) client.followExpiry() }
                assertTrue(env.api.connHandshakePtoCeilingHeld(pair.client), "held until the acknowledgement arrives")
                pair.deliver(held, pair.client)
                client.step()
                assertEquals(false, env.api.connHandshakePtoCeilingHeld(pair.client), "released by the held acknowledgement")
            } finally {
                client.pair.close()
            }
        }

    private fun shown(ceiling: PtoCeiling): String =
        when (ceiling) {
            is PtoCeiling.At -> ceiling.nanos.nanoseconds.toString()
            is PtoCeiling.Growing -> "${ceiling.firstNanos.nanoseconds} ×${ceiling.growth} ≤ ${ceiling.maxNanos.nanoseconds}"
            is PtoCeiling.None -> "none"
        }

    private companion object {
        val ONE_WAY = 30.milliseconds
        val HELD = 4.seconds
        val WAIT = 30.seconds
        val MAX_ACK_DELAY = 25.milliseconds

        /** The longest an ACK hold lasts: the advertised max_ack_delay less the loop's one-millisecond wait step. */
        val LONGEST_HOLD = 24.milliseconds
        const val MAX_ROUNDS = 64
    }
}
