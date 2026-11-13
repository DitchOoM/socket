package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals

class AckHoldGateTests {
    private fun AckHoldGate.offers(turns: Int): List<HoldDecision> = List(turns) { offer() }

    private fun AckHoldGate.missedHold() {
        assertEquals(HoldDecision.Hold, offer())
        missed()
    }

    @Test
    fun aGateThatHasSeenNothingHolds() {
        assertEquals(HoldDecision.Hold, AckHoldGate().offer())
    }

    @Test
    fun oneMissAloneDoesNotShutTheGate() {
        val gate = AckHoldGate()
        gate.missedHold()
        assertEquals(HoldDecision.Hold, gate.offer())
    }

    @Test
    fun aReplyBetweenMissesKeepsTheGateOpen() {
        val gate = AckHoldGate()
        repeat(10) {
            gate.missedHold()
            assertEquals(HoldDecision.Hold, gate.offer())
            gate.replied()
        }
        assertEquals(HoldDecision.Hold, gate.offer())
    }

    @Test
    fun twoMissesInARowShutTheGateForTheFirstShutLength() {
        val gate = AckHoldGate()
        repeat(AckHoldGate.MISSES_BEFORE_SHUT) { gate.missedHold() }
        assertEquals(List(AckHoldGate.FIRST_SHUT_TURNS) { HoldDecision.FlushNow }, gate.offers(AckHoldGate.FIRST_SHUT_TURNS))
        assertEquals(HoldDecision.Hold, gate.offer(), "the turn after the shut is the probation hold")
    }

    @Test
    fun aMissOnProbationShutsTheGateForTwiceAsLongUpToTheLongest() {
        val gate = AckHoldGate()
        repeat(AckHoldGate.MISSES_BEFORE_SHUT) { gate.missedHold() }
        var shut = AckHoldGate.FIRST_SHUT_TURNS
        repeat(8) {
            assertEquals(List(shut) { HoldDecision.FlushNow }, gate.offers(shut), "shut for $shut turns")
            gate.missedHold()
            shut = (shut * 2).coerceAtMost(AckHoldGate.LONGEST_SHUT_TURNS)
        }
        assertEquals(AckHoldGate.LONGEST_SHUT_TURNS, shut)
    }

    @Test
    fun aReplyOnProbationOpensTheGateAndForgetsTheBackOff() {
        val gate = AckHoldGate()
        repeat(AckHoldGate.MISSES_BEFORE_SHUT) { gate.missedHold() }
        gate.offers(AckHoldGate.FIRST_SHUT_TURNS)
        gate.missedHold()
        gate.offers(AckHoldGate.FIRST_SHUT_TURNS * 2)
        assertEquals(HoldDecision.Hold, gate.offer())
        gate.replied()
        // Open again: one miss is tolerated, and the next shut is the first length, not a doubled one.
        gate.missedHold()
        gate.missedHold()
        assertEquals(List(AckHoldGate.FIRST_SHUT_TURNS) { HoldDecision.FlushNow }, gate.offers(AckHoldGate.FIRST_SHUT_TURNS))
        assertEquals(HoldDecision.Hold, gate.offer())
    }

    @Test
    fun turnWorkKeepsTheStrongestThingATurnDid() {
        assertEquals(TurnWork.DatagramsOnly, TurnWork.TimerOnly.after(TurnWork.DatagramsOnly))
        assertEquals(TurnWork.Other, TurnWork.DatagramsOnly.after(TurnWork.Other))
        assertEquals(TurnWork.ApplicationWrite, TurnWork.Other.after(TurnWork.ApplicationWrite))
        assertEquals(TurnWork.ApplicationWrite, TurnWork.ApplicationWrite.after(TurnWork.DatagramsOnly))
        assertEquals(TurnWork.Other, TurnWork.Other.after(TurnWork.DatagramsOnly))
    }
}
