package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

/**
 * How many received datagrams the driver loop reads before it flushes, and so how often quiche writes
 * an ACK (quiche writes its ACK in the flush). Decided once per loop turn by [AckCadenceMemory].
 */
internal enum class AckCadence(
    val receivesPerFlush: Int,
) {
    /**
     * A path that has shown no loss: an ACK for every second datagram, the number RFC 9000 §13.2.2 gives
     * ("a receiver SHOULD send an ACK frame after receiving at least two ack-eliciting packets"). Halves
     * the ACK datagrams of a bulk receive.
     */
    Relaxed(receivesPerFlush = 2),

    /**
     * A path that has shown loss within the memory: an ACK for every datagram, as before #813. Each ACK
     * is another chance to get the peer's picture of what arrived through a link that drops datagrams, and
     * on a lossy path a lost ACK costs a probe timeout, which backs off.
     */
    EveryDatagram(receivesPerFlush = 1),
}

/** Where a counter of loss evidence comes from: what the connection sent, or what it received. */
internal enum class EvidenceSource {
    /** `lost` + `retrans`: packets this endpoint sent that were declared lost or sent again as a probe. */
    PacketsLost,

    /** Ack-eliciting packets that arrived beyond the next expected packet number: a gap or a reorder. */
    ReceiveGap,
}

/** What one reading of an [EvidenceSource]'s counter says against the one before it. */
internal sealed interface LossEvidence {
    /** The counters did not grow. */
    data object Unchanged : LossEvidence

    /** The counter grew since the last reading: a loss, a probe, or a gap, depending on the source. */
    data object Grew : LossEvidence
}

/**
 * Remembers whether the path has shown loss lately, so the loop can pick an [AckCadence] once per turn.
 *
 * There are two sources of evidence ([EvidenceSource]), each a monotonic counter read once per turn:
 *  - [EvidenceSource.PacketsLost]: the connection's own sender-side counters (`lost` + `retrans` of
 *    `quiche_conn_stats`), packets this endpoint sent that were declared lost or sent again as a probe;
 *  - [EvidenceSource.ReceiveGap]: ack-eliciting packets that arrived after a gap in the packet numbers
 *    (`quiche_conn_recv_gaps`, a patched-in export), RFC 9000 §13.2.1's trigger for an immediate ACK. It
 *    is the only evidence a pure receiver has once its handshake is over, and it fires on a reorder too.
 *
 * Once evidence grows, the path counts as lossy for [LOSS_MEMORY_RTTS] round trips ([remember]); after
 * that long without new evidence the cadence relaxes again. The memory is in round trips, not turns,
 * because how many turns a round trip takes depends on how the peer's datagrams arrive. Four was the
 * smallest of 1, 2, 4, 8 and 16 that recovered the sim's misses on the 800 ms, 30 %-loss path (see
 * [QuicheDriver.dispatchQueued]); at 20 and 100 ms and on iid loss 2, 4 and 8 recover alike, and on bursty
 * loss no hold recovers what an ACK per datagram does, because the receiver does not see the loss.
 *
 * Confined to the driver loop, like everything the loop reads: no lock, no atomics.
 */
internal class AckCadenceMemory {
    private sealed interface Memory {
        data object Clean : Memory

        class Lossy(
            val since: TimeMark,
            val holdFor: Duration,
        ) : Memory
    }

    private var memory: Memory = Memory.Clean
    private var seenLost = 0L
    private var seenGaps = 0L

    /** Compare [source]'s counter with its previous reading; the first reading is against zero. */
    fun observe(
        source: EvidenceSource,
        counter: Long,
    ): LossEvidence {
        val seen =
            when (source) {
                EvidenceSource.PacketsLost -> seenLost
                EvidenceSource.ReceiveGap -> seenGaps
            }
        when (source) {
            EvidenceSource.PacketsLost -> seenLost = counter
            EvidenceSource.ReceiveGap -> seenGaps = counter
        }
        return if (counter > seen) LossEvidence.Grew else LossEvidence.Unchanged
    }

    /** New loss evidence at [now]: lossy for [LOSS_MEMORY_RTTS] of [rtt], never less than one timer granularity's worth. */
    fun remember(
        now: TimeMark,
        rtt: Duration,
    ) {
        memory = Memory.Lossy(now, rtt.coerceAtLeast(TIMER_GRANULARITY) * LOSS_MEMORY_RTTS)
    }

    /** The cadence for this turn. */
    fun cadence(): AckCadence =
        when (val m = memory) {
            Memory.Clean -> AckCadence.Relaxed
            is Memory.Lossy ->
                if (m.since.elapsedNow() < m.holdFor) {
                    AckCadence.EveryDatagram
                } else {
                    memory = Memory.Clean
                    AckCadence.Relaxed
                }
        }

    internal companion object {
        /** Round trips a path stays lossy after the last loss evidence. */
        const val LOSS_MEMORY_RTTS = 4

        /** RFC 9002 §6.1.2 `kGranularity`: no timer is finer, so no round trip counts as shorter. */
        val TIMER_GRANULARITY = 1.milliseconds
    }
}
