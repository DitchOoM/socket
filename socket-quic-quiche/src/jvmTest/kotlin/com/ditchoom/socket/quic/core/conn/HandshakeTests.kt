package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.PtoCeiling
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.HandshakeReached
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.life.RttSample
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [Handshake] against a scripted quiche and plain times: its ceiling sequence, its deadline and its report. */
class HandshakeTests {
    private val ms = 1_000_000L
    private val stub = StubQuicheApi().apply { established = false }
    private var confirmed = false
    private val api: QuicheApi =
        object : QuicheApi by stub {
            override fun connIsHandshakeConfirmed(conn: QuicheConn) = confirmed
        }
    private val q = QuicheConnRef(api, QuicheConn(1L))
    private val path = SinglePath(q, SockAddr(0L, 0), SockAddr(0L, 0), SendTarget.ConnectedPeer)

    /** Wi-Fi's prior: 80 ms, so quiche's first PTO and the first ceiling are 240 ms. */
    private val wifi = HandshakeRule.adaptive(80 * ms, 25 * ms, HandshakeBound.Bounded(10_000 * ms), LinkClass.Wifi)

    private fun handshake(
        rule: HandshakeRule = wifi,
        startedNanos: Long = 0L,
    ) = Handshake(q, rule, startedNanos, nativeBytes(255), ChainSource.NotAsked, BufferFactory.network())

    /** Each ceiling quiche was given, as (first, growth, max) nanoseconds. */
    private fun ceilings(): List<Triple<Long, Int, Long>> =
        stub.ptoCeilings.map {
            when (it) {
                is PtoCeiling.None -> Triple(0L, 0, 0L)
                is PtoCeiling.At -> Triple(it.nanos, 1, it.nanos)
                is PtoCeiling.Growing -> Triple(it.firstNanos, it.growth, it.maxNanos)
            }
        }

    private fun quicheSays(
        probes: Long,
        rtt: Duration = Duration.ZERO,
        received: Long = 0,
    ) {
        stub.pathStats = listOf(stats(probes, rtt, received))
    }

    @Test
    fun theHandshakeReportsEstablishmentOnceWithItsDuration() {
        val handshake = handshake(startedNanos = 100L)
        assertEquals(HandshakeStep.Handshaking, handshake.step(Nanos(200L)))
        stub.established = true
        confirmed = true
        assertEquals(HandshakeStep.JustEstablished, handshake.step(Nanos(300L)))
        assertEquals(HandshakeStep.Established, handshake.step(Nanos(400L)))
        assertEquals(400L, handshake.report(Nanos(500L), path.read(), FULL).handshakeNanos)
    }

    /**
     * Wi-Fi's rule gives quiche its ceiling once, before the first flight: 240 ms, 3× per PTO expiry, at most an hour.
     * quiche grows, restarts and releases it; expiries, establishment and confirmation never set it again.
     */
    @Test
    fun theAdaptiveRuleGivesQuicheItsGrowingCeilingOnce() {
        val handshake = handshake()
        val rule = Triple(240 * ms, 3, 3_600_000 * ms)
        assertEquals(listOf(rule), ceilings(), "the ceiling is given before the first flight")
        quicheSays(probes = 1, rtt = 2.seconds)
        handshake.expired(path.read())
        quicheSays(probes = 4, rtt = 2.seconds)
        handshake.expired(path.read())
        stub.established = true
        assertEquals(HandshakeStep.JustEstablished, handshake.step(Nanos(50 * ms)))
        confirmed = true
        assertEquals(HandshakeStep.Established, handshake.step(Nanos(80 * ms)))
        assertEquals(listOf(rule), ceilings(), "never set again")
        assertEquals(4, handshake.report(Nanos(90 * ms), path.read(), FULL).probes, "the expiries are counted for the report")
        assertEquals(Confirmation.Confirmed, handshake.confirmation())
        assertEquals(HandshakeStep.Established, handshake.step(Nanos(20_000 * ms)), "no deadline after confirmation")
        val sink = WakeSink()
        handshake.offerWake(sink)
        assertEquals(WakeState.NotAsked, sink.state())
    }

    @Test
    fun rfc9002PacingNeverSetsACeiling() {
        val handshake = handshake(HandshakeRule(PtoRule.Rfc9002, HandshakeBound.UntilClosed, LinkClass.Wifi))
        quicheSays(probes = 3)
        handshake.expired(path.read())
        stub.established = true
        confirmed = true
        handshake.step(Nanos(0L))
        assertEquals(emptyList(), ceilings())
    }

    /** A peer that acknowledges every Initial and never completes: the deadline ends it, not the idle timeout. */
    @Test
    fun aHandshakeThatNeverCompletesPassesItsDeadlineWithItsProgress() {
        val handshake = handshake(startedNanos = 5 * ms)
        val sink = WakeSink()
        handshake.offerWake(sink)
        assertEquals(10_005 * ms, sink.earliest().value, "the deadline is offered as a wake")
        quicheSays(probes = 6, rtt = 30.milliseconds, received = 40)
        assertEquals(HandshakeStep.Handshaking, handshake.step(Nanos(10_004 * ms)))
        assertEquals(HandshakeStep.DeadlinePassed, handshake.step(Nanos(10_005 * ms)))
        val progress = handshake.progress(Nanos(10_005 * ms), path.read())
        assertEquals(10.seconds, progress.waited)
        assertEquals(HandshakeReached.PeerAnswered, progress.reached)
        assertEquals(6, progress.probes)
        assertEquals(80.milliseconds, progress.prior)
        assertEquals(LinkClass.Wifi, progress.link)
        assertEquals(30.milliseconds, assertIs<RttSample.Sampled>(progress.firstSample).rtt)
    }

    @Test
    fun aHandshakeThatHeardNothingSaysSo() {
        val handshake = handshake()
        quicheSays(probes = 6)
        assertEquals(HandshakeStep.DeadlinePassed, handshake.step(Nanos(10_000 * ms)))
        val progress = handshake.progress(Nanos(10_000 * ms), path.read())
        assertEquals(HandshakeReached.NothingReceived, progress.reached)
        assertIs<RttSample.None>(progress.firstSample)
    }

    @Test
    fun anEstablishedHandshakeHasNoDeadlineWhileItIsNotConfirmed() {
        val handshake = handshake()
        stub.established = true
        handshake.step(Nanos(10 * ms))
        assertEquals(HandshakeStep.Established, handshake.step(Nanos(10_000 * ms)))
        val sink = WakeSink()
        handshake.offerWake(sink)
        assertEquals(WakeState.NotAsked, sink.state(), "the deadline is no longer offered")
    }

    @Test
    fun untilClosedHasNoDeadline() {
        val rule = HandshakeRule.adaptive(80 * ms, 25 * ms, HandshakeBound.UntilClosed, LinkClass.Wifi)
        val handshake = handshake(rule)
        val sink = WakeSink()
        handshake.offerWake(sink)
        assertEquals(WakeState.NotAsked, sink.state())
        assertEquals(HandshakeStep.Handshaking, handshake.step(Nanos(3_600_000 * ms)))
    }

    @Test
    fun theReportSaysWhyAConnectWasSlow() {
        stub.established = true
        confirmed = true

        fun reportAfter(
            tookMs: Long,
            probes: Long,
            rtt: Duration,
        ): ConnectPace {
            val handshake = handshake()
            handshake.step(Nanos(tookMs * ms))
            quicheSays(probes, rtt, received = 4)
            return handshake.report(Nanos(tookMs * ms), path.read(), FULL).pace
        }
        assertIs<ConnectPace.AsExpected>(reportAfter(tookMs = 30, probes = 0, rtt = 20.milliseconds))
        assertEquals(SlowConnect.RttAbovePrior, assertIs<ConnectPace.Slow>(reportAfter(2_100, 0, 2.seconds)).why)
        assertEquals(SlowConnect.ProbesNeeded, assertIs<ConnectPace.Slow>(reportAfter(300, 1, 20.milliseconds)).why)
        assertEquals(SlowConnect.PeerSlowToAnswer, assertIs<ConnectPace.Slow>(reportAfter(200, 0, 20.milliseconds)).why)
        assertEquals(SlowConnect.ProbesNeeded, assertIs<ConnectPace.Slow>(reportAfter(300, 1, Duration.ZERO)).why, "no sample")
    }

    private fun stats(
        probes: Long,
        rtt: Duration,
        received: Long,
    ) = QuicPathStats(
        validationState = 0,
        active = true,
        recv = received,
        sent = 0,
        lost = 0,
        retrans = 0,
        totalPtoCount = probes,
        rtt = if (rtt == Duration.ZERO) 333.milliseconds else rtt,
        minRtt = rtt,
        maxRtt = rtt,
        rttvar = Duration.ZERO,
        cwnd = 0,
        sentBytes = 0,
        recvBytes = 0,
        lostBytes = 0,
        streamRetransBytes = 0,
        pmtu = 0,
        deliveryRate = 0,
    )
}

private val FULL = QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered)
