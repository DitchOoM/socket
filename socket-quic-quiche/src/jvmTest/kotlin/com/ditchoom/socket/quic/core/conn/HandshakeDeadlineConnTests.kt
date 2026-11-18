package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.PtoCeiling
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.HandshakeReached
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.time.WaitResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The handshake's deadline and ceiling as a connection served by its loop applies them: virtual time, no socket. */
class HandshakeDeadlineConnTests {
    private val ms = 1_000_000L
    private val api = StubQuicheApi().apply { established = false }

    private fun rig(bound: HandshakeBound) =
        ConnRig(
            api,
            rules =
                SlotRules.forWaits(
                    WaitResolution(ms),
                    HandshakeRule.adaptive(80 * ms, 25 * ms, bound, LinkClass.Wifi),
                    SlotRules.Default.close,
                ),
        )

    /**
     * A peer that acknowledges every Initial probe and never completes the handshake keeps quiche's idle timer alive
     * (the kwik interop run waited 270 s). The connection ends at its 10 s bound, typed, with what it reached.
     */
    @Test
    fun aPeerThatAnswersButNeverCompletesEndsAtTheBoundAndNotTheIdleTimeout() {
        val rig = rig(HandshakeBound.Bounded(10_000 * ms))
        api.connTimeout = 500.milliseconds
        var now = 0L
        rig.pass(now)
        var probes = 0L
        while (rig.sink.ended.isEmpty() && now < 300_000 * ms) {
            now += 100 * ms
            probes++
            api.pathStats = listOf(stats(probes = probes / 5, received = probes))
            rig.pass(now)
        }
        assertEquals(10_000 * ms, now, "ended at the bound")
        val end = assertIs<ConnEnd.HandshakeTimedOut>(rig.sink.ended.single())
        assertEquals(10.seconds, end.progress.waited)
        assertEquals(HandshakeReached.PeerAnswered, end.progress.reached)
        assertEquals(listOf<QuicError>(QuicError.NoError), api.closeErrors, "the peer is told")
    }

    /** The bound is for the handshake, as the caller sees it complete: a withheld HANDSHAKE_DONE is silence, which the idle timeout owns. */
    @Test
    fun aHandshakeThatCompletedIsNotEndedByTheBoundWhileItsConfirmationIsWithheld() {
        val rig = rig(HandshakeBound.Bounded(10_000 * ms))
        api.connTimeout = 500.milliseconds
        api.withholdConfirmation = true
        rig.pass(0)
        api.established = true
        var now = 0L
        while (now < 30_000 * ms) {
            now += 100 * ms
            rig.pass(now)
        }
        assertEquals(1, rig.sink.established.size)
        assertEquals(emptyList(), rig.sink.ended, "the connection the caller holds stays open past the bound")
    }

    @Test
    fun theDeadlineWakesTheConnectionWhenNothingElseWould() {
        val rig = rig(HandshakeBound.Bounded(10_000 * ms))
        api.connTimeout = null
        rig.pass(0)
        val due = rig.pass(1)
        assertEquals(10_000 * ms, due.fold({ 0L }, { it }), "the loop sleeps until the bound")
        rig.pass(10_000 * ms)
        assertIs<ConnEnd.HandshakeTimedOut>(rig.sink.ended.single())
    }

    /** The connection gives quiche its ceiling once; its timer expiries are counted for the report until confirmation. */
    @Test
    fun theConnectionGivesQuicheItsCeilingOnceAndCountsItsExpiries() {
        val rig = rig(HandshakeBound.UntilClosed)
        api.connTimeout = 240.milliseconds
        rig.pass(0)
        api.pathStats = listOf(stats(probes = 1, received = 0))
        rig.pass(240 * ms)
        api.established = true
        rig.pass(480 * ms)
        rig.pass(720 * ms)
        val given = assertIs<PtoCeiling.Growing>(api.ptoCeilings.single())
        assertEquals(listOf(240 * ms, 3L, 3_600_000 * ms), listOf(given.firstNanos, given.growth.toLong(), given.maxNanos))
        assertEquals(1, rig.sink.established.size)
        assertEquals(
            1,
            rig.sink.established
                .single()
                .probes,
        )
    }

    private fun stats(
        probes: Long,
        received: Long,
    ) = QuicPathStats(
        validationState = 0,
        active = true,
        recv = received,
        sent = 0,
        lost = 0,
        retrans = 0,
        totalPtoCount = probes,
        rtt = 333.milliseconds,
        minRtt = Duration.ZERO,
        maxRtt = Duration.ZERO,
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
