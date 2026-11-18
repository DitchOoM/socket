package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import kotlin.test.Test
import kotlin.test.assertEquals

/** [AckHold]'s decisions over scripted turns: when it holds, how long, and how it backs off and comes back. */
class AckHoldTests {
    private val hold = AckHold(AckRule.HoldForReply(maxNanos = 24_000_000L, rttShare = 4))
    private var now = 0L
    private var handed = 0L
    private var output = 0L
    private var gate = HoldGate.Open
    private var rtt = 40_000_000L

    private fun turn(): AckStep =
        when (hold.decide(Nanos(now), handed, output)) {
            AckAsk.Write -> AckStep.Write
            AckAsk.Gate -> hold.gated(Nanos(now), gate, rtt)
        }

    /** A turn that read one datagram whose data went to a parked reader. */
    private fun requestTurn(): AckStep {
        hold.received()
        handed++
        return turn()
    }

    private fun replyTurn(): AckStep {
        output++
        return turn()
    }

    private fun deadlineTurn(): AckStep {
        val sink = WakeSink()
        hold.offerWake(sink)
        assertEquals(WakeState.Asked, sink.state(), "a held ACK offers its deadline")
        now = sink.earliest().value
        hold.mustWrite()
        return turn()
    }

    private fun miss() {
        assertEquals(AckStep.Hold, requestTurn())
        assertEquals(AckStep.Write, deadlineTurn())
    }

    @Test
    fun aReplyEndsTheHoldAndTheHoldLastsAQuarterOfTheRtt() {
        assertEquals(AckStep.Hold, requestTurn())
        val sink = WakeSink()
        hold.offerWake(sink)
        assertEquals(10_000_000L, sink.earliest().value, "a quarter of 40 ms")
        assertEquals(AckStep.Write, replyTurn())
        assertEquals(1L, hold.hits)
    }

    @Test
    fun theHoldIsCappedBelowTheAdvertisedMaxAckDelay() {
        rtt = 400_000_000L
        requestTurn()
        val sink = WakeSink()
        hold.offerWake(sink)
        assertEquals(24_000_000L, sink.earliest().value)
    }

    @Test
    fun aTurnWithoutHandedDataOrWithAClosedGateWritesAtOnce() {
        hold.received()
        assertEquals(AckStep.Write, turn(), "nothing was handed up")
        gate = HoldGate.LossRecent
        assertEquals(AckStep.Write, requestTurn(), "loss closes the gate")
        gate = HoldGate.Open
        repeat(AckHold.HOLD_DATAGRAMS + 1) { hold.received() }
        handed++
        assertEquals(AckStep.Write, turn(), "more than two datagrams are acknowledged at once")
    }

    @Test
    fun workWithoutOutputKeepsTheHoldAndMoreDatagramsEndIt() {
        assertEquals(AckStep.Hold, requestTurn())
        assertEquals(AckStep.Hold, turn(), "a read run as work sends nothing")
        assertEquals(AckStep.Hold, requestTurn(), "two datagrams in a hold")
        assertEquals(AckStep.Write, requestTurn(), "the third is acknowledged at once")
    }

    @Test
    fun missesBackOffThenTrialsDoubleUntilAHitRestoresHolding() {
        repeat(AckHold.MISSES_TO_BACK_OFF) { miss() }
        var span = AckHold.FIRST_SKIP
        repeat(4) {
            repeat(span) { assertEquals(AckStep.Write, requestTurn(), "backed off: at once") }
            miss()
            span = minOf(span * 2, AckHold.MAX_SKIP)
        }
        assertEquals(AckHold.MAX_SKIP, span, "the skip stops doubling at its cap")
        repeat(span) { assertEquals(AckStep.Write, requestTurn()) }
        assertEquals(AckStep.Hold, requestTurn(), "a trial")
        assertEquals(AckStep.Write, replyTurn(), "its reply landed")
        assertEquals(AckStep.Hold, requestTurn(), "holding again at once")
    }
}
