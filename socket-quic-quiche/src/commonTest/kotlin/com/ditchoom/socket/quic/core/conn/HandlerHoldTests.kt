package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import kotlin.test.Test
import kotlin.test.assertEquals

/** [HandlerHold]'s decisions over scripted turns: it waits only while a handler it resumed runs, and every gate ends it. */
class HandlerHoldTests {
    private val rule = AckRule.untilHandlerDone(WaitResolution(1_000_000L)) as AckRule.UntilHandlerDone
    private val handlers = RunningHandlers()
    private val hold = HandlerHold(rule, handlers)
    private var now = 1_000L
    private var handed = 0L
    private var output = 0L
    private var gate = HoldGate.Open

    private fun turn(): AckStep =
        when (hold.decide(Nanos(now), handed, output)) {
            HandlerAsk.Write -> AckStep.Write
            HandlerAsk.UntilPassEnds -> AckStep.UntilPassEnds
            HandlerAsk.Gate -> hold.gated(Nanos(now), gate)
        }

    /** A turn that read one datagram and resumed a parked reader's caller with its data. */
    private fun requestTurn(): AckStep {
        hold.received()
        handed++
        handlers.began()
        return turn()
    }

    @Test
    fun aReplyCarriesTheAckOfTheRequestItsHandlerWasResumedWith() {
        assertEquals(AckStep.UntilReply, requestTurn())
        output++
        assertEquals(AckStep.Write, turn())
        assertEquals(ServeNow.No, handlers.ended(), "the reply already carried the ACK: nothing to serve")
        assertEquals(1L, hold.replied)
    }

    @Test
    fun aHandlerThatEndsWithoutAReplyAsksForTheAckAtOnce() {
        assertEquals(AckStep.UntilReply, requestTurn())
        assertEquals(ServeNow.Yes, handlers.ended(), "the last handler ended while the ACK waited")
        assertEquals(AckStep.Write, turn())
        assertEquals(1L, hold.done)
    }

    @Test
    fun aHandlerThatEndedBeforeTheWaitWasPublishedDoesNotHoldIt() {
        hold.received()
        handed++
        handlers.began()
        val asked = hold.decide(Nanos(now), handed, output)
        assertEquals(HandlerAsk.Gate, asked)
        assertEquals(ServeNow.No, handlers.ended(), "no wait was published yet")
        assertEquals(AckStep.Write, hold.gated(Nanos(now), gate), "the count read after publishing finds nobody running")
    }

    @Test
    fun aHandlerThatRunsOnIsCappedWithinTheAdvertisedMaxAckDelay() {
        assertEquals(AckStep.UntilReply, requestTurn())
        val sink = WakeSink()
        hold.offerWake(sink)
        assertEquals(WakeState.Asked, sink.state())
        assertEquals(now + rule.cap.nanos, sink.earliest().value)
        assertEquals(24_000_000L, rule.cap.nanos, "25 ms less one wait step")
        now = sink.earliest().value - 1
        assertEquals(AckStep.UntilReply, turn(), "still within the cap")
        now++
        assertEquals(AckStep.Write, turn())
        assertEquals(1L, hold.capped)
        assertEquals(Stale.Yes, hold.deadlineLeft())
    }

    @Test
    fun withNoHandlerRunningTheAckWaitsOnlyForTheEndOfThePass() {
        hold.received()
        handed++
        assertEquals(AckStep.UntilPassEnds, turn())
        assertEquals(AckStep.Write, turn())
        assertEquals(0L, hold.holds)
    }

    @Test
    fun everyClosedGateSendsTheAckAtOnce() {
        for (closed in HoldGate.entries - HoldGate.Open) {
            gate = closed
            assertEquals(AckStep.Write, requestTurn(), "$closed: at once")
            handlers.ended()
        }
        assertEquals(0L, hold.holds)
    }

    @Test
    fun aGateThatClosesDuringAWaitEndsIt() {
        assertEquals(AckStep.UntilReply, requestTurn())
        gate = HoldGate.LossRecent
        hold.received()
        assertEquals(AckStep.Write, turn())
        assertEquals(1L, hold.capped)
    }

    @Test
    fun aThirdDatagramDuringAWaitIsAcknowledgedAtOnce() {
        assertEquals(AckStep.UntilReply, requestTurn())
        hold.received()
        assertEquals(AckStep.UntilReply, turn(), "two ack-eliciting datagrams may wait")
        hold.received()
        assertEquals(AckStep.Write, turn())
    }

    @Test
    fun newsThatMustBeWrittenEndsAWait() {
        assertEquals(AckStep.UntilReply, requestTurn())
        hold.mustWrite()
        assertEquals(AckStep.Write, turn())
    }
}
