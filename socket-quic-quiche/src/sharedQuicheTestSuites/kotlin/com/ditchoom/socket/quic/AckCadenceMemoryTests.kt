package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

/** The decision [AckCadenceMemory] makes from loss counters and a clock, with the clock moved by hand. */
class AckCadenceMemoryTests {
    @Test
    fun theMemoryOfLossFadesAfterFourRoundTripsAndANewLossRenewsIt() {
        val clock = FakeMarks()
        val memory = AckCadenceMemory()
        assertEquals(AckCadence.Relaxed, memory.cadence(), "no loss seen yet")

        assertEquals(LossEvidence.Grew, memory.observe(EvidenceSource.PacketsLost, 1))
        memory.remember(clock.markNow(), RTT)
        assertEquals(AckCadence.EveryDatagram, memory.cadence())

        clock.now += RTT * (AckCadenceMemory.LOSS_MEMORY_RTTS - 1)
        assertEquals(AckCadence.EveryDatagram, memory.cadence(), "still inside the memory")
        assertEquals(LossEvidence.Unchanged, memory.observe(EvidenceSource.PacketsLost, 1), "a counter that did not grow is not new loss")

        clock.now += RTT
        assertEquals(AckCadence.Relaxed, memory.cadence(), "four round trips with no new loss")

        assertEquals(LossEvidence.Grew, memory.observe(EvidenceSource.PacketsLost, 2))
        memory.remember(clock.markNow(), RTT)
        assertEquals(AckCadence.EveryDatagram, memory.cadence(), "new loss starts the memory again")
    }

    @Test
    fun aRoundTripShorterThanTheTimerGranularityIsNotShorterThanIt() {
        val clock = FakeMarks()
        val memory = AckCadenceMemory()
        memory.observe(EvidenceSource.PacketsLost, 1)
        memory.remember(clock.markNow(), Duration.ZERO)
        clock.now += AckCadenceMemory.TIMER_GRANULARITY * (AckCadenceMemory.LOSS_MEMORY_RTTS - 1)
        assertEquals(AckCadence.EveryDatagram, memory.cadence())
        clock.now += AckCadenceMemory.TIMER_GRANULARITY
        assertEquals(AckCadence.Relaxed, memory.cadence())
    }

    @Test
    fun aCounterThatFallsIsNotLoss() {
        val memory = AckCadenceMemory()
        memory.observe(EvidenceSource.PacketsLost, 5)
        assertEquals(LossEvidence.Unchanged, memory.observe(EvidenceSource.PacketsLost, 3))
        assertEquals(LossEvidence.Grew, memory.observe(EvidenceSource.PacketsLost, 4), "the baseline follows the counter down")
    }

    @Test
    fun theTwoSourcesKeepTheirOwnBaselines() {
        val memory = AckCadenceMemory()
        assertEquals(LossEvidence.Grew, memory.observe(EvidenceSource.ReceiveGap, 3))
        assertEquals(
            LossEvidence.Unchanged,
            memory.observe(EvidenceSource.PacketsLost, 0),
            "gaps counted by the receive side do not move the sender-side baseline",
        )
        assertEquals(LossEvidence.Grew, memory.observe(EvidenceSource.PacketsLost, 2))
        assertEquals(LossEvidence.Unchanged, memory.observe(EvidenceSource.ReceiveGap, 3))
        assertEquals(LossEvidence.Grew, memory.observe(EvidenceSource.ReceiveGap, 4))
    }

    /** A clock the test moves by hand. */
    private class FakeMarks {
        var now: Duration = Duration.ZERO

        fun markNow(): TimeMark {
            val origin = now
            return object : TimeMark {
                override fun elapsedNow(): Duration = now - origin
            }
        }
    }

    private companion object {
        val RTT = 100.milliseconds
    }
}
