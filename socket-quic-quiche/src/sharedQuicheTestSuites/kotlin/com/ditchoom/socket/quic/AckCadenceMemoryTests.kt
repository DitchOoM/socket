package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

/** The decision [AckCadenceMemory] makes from loss counters and a clock, with the clock moved by hand. */
class AckCadenceMemoryTests {
    private val clock = FakeMarks()
    private val memory = AckCadenceMemory()

    /** Read [source]'s counter at [counter] and let the memory act on what grew, as the loop does. */
    private fun read(
        source: EvidenceSource,
        counter: Long,
    ) {
        val evidence = memory.observe(source, counter)
        if (evidence is LossEvidence.Grew) memory.remember(source, evidence, clock.markNow(), RTT)
    }

    @Test
    fun theMemoryOfLossFadesAfterFourRoundTripsAndANewLossRenewsIt() {
        assertEquals(AckCadence.Relaxed, memory.cadence(), "no loss seen yet")

        read(EvidenceSource.PacketsLost, 1)
        assertEquals(AckCadence.EveryDatagram, memory.cadence())

        clock.now += RTT * (AckCadenceMemory.LOSS_MEMORY_RTTS - 1)
        assertEquals(AckCadence.EveryDatagram, memory.cadence(), "still inside the memory")
        read(EvidenceSource.PacketsLost, 1)
        assertEquals(AckCadence.EveryDatagram, memory.cadence(), "a counter that did not grow is not new loss")

        clock.now += RTT
        assertEquals(AckCadence.Relaxed, memory.cadence(), "four round trips with no new loss")

        read(EvidenceSource.PacketsLost, 2)
        assertEquals(AckCadence.EveryDatagram, memory.cadence(), "new loss starts the memory again")
    }

    @Test
    fun aRoundTripShorterThanTheTimerGranularityIsNotShorterThanIt() {
        memory.remember(EvidenceSource.PacketsLost, LossEvidence.Grew(1), clock.markNow(), Duration.ZERO)
        clock.now += AckCadenceMemory.TIMER_GRANULARITY * (AckCadenceMemory.LOSS_MEMORY_RTTS - 1)
        assertEquals(AckCadence.EveryDatagram, memory.cadence())
        clock.now += AckCadenceMemory.TIMER_GRANULARITY
        assertEquals(AckCadence.Relaxed, memory.cadence())
    }

    @Test
    fun aCounterThatFallsIsNotLoss() {
        memory.observe(EvidenceSource.PacketsLost, 5)
        assertEquals(LossEvidence.Unchanged, memory.observe(EvidenceSource.PacketsLost, 3))
        assertIs<LossEvidence.Grew>(memory.observe(EvidenceSource.PacketsLost, 4), "the baseline follows the counter down")
    }

    @Test
    fun theTwoSourcesKeepTheirOwnBaselines() {
        assertEquals(3, assertIs<LossEvidence.Grew>(memory.observe(EvidenceSource.ReceiveGap, 3)).by)
        assertEquals(
            LossEvidence.Unchanged,
            memory.observe(EvidenceSource.PacketsLost, 0),
            "gaps counted by the receive side do not move the sender-side baseline",
        )
        assertIs<LossEvidence.Grew>(memory.observe(EvidenceSource.PacketsLost, 2))
        assertEquals(LossEvidence.Unchanged, memory.observe(EvidenceSource.ReceiveGap, 3))
        assertEquals(1, assertIs<LossEvidence.Grew>(memory.observe(EvidenceSource.ReceiveGap, 4)).by)
    }

    @Test
    fun aLoneReceiveGapOnlyMakesThePathASuspect() {
        read(EvidenceSource.ReceiveGap, 1)
        assertEquals(AckCadence.Relaxed, memory.cadence(), "one gap is what a socket buffer's burst of drops leaves")

        clock.now += RTT * (AckCadenceMemory.LOSS_MEMORY_RTTS - 1)
        read(EvidenceSource.ReceiveGap, 2)
        assertEquals(AckCadence.EveryDatagram, memory.cadence(), "a second gap inside the same time makes it lossy")

        clock.now += RTT * AckCadenceMemory.LOSS_MEMORY_RTTS
        assertEquals(AckCadence.Relaxed, memory.cadence())
    }

    @Test
    fun aReceiveGapAfterTheSuspicionHasFadedStartsItAgain() {
        read(EvidenceSource.ReceiveGap, 1)
        clock.now += RTT * AckCadenceMemory.LOSS_MEMORY_RTTS
        assertEquals(AckCadence.Relaxed, memory.cadence())
        read(EvidenceSource.ReceiveGap, 2)
        assertEquals(AckCadence.Relaxed, memory.cadence(), "the first gap was too long ago to repeat")
        read(EvidenceSource.ReceiveGap, 3)
        assertEquals(AckCadence.EveryDatagram, memory.cadence())
    }

    @Test
    fun twoReceiveGapsInOneReadingAreAlreadyRepeated() {
        read(EvidenceSource.ReceiveGap, 2)
        assertEquals(AckCadence.EveryDatagram, memory.cadence())
    }

    @Test
    fun aSentPacketLossCountsAtOnceWhateverTheReceiveSideSaw() {
        read(EvidenceSource.ReceiveGap, 1)
        read(EvidenceSource.PacketsLost, 1)
        assertEquals(AckCadence.EveryDatagram, memory.cadence())
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
