package com.ditchoom.socket.quic

/**
 * The longest PTO interval quiche may wait, held by each path until its first RTT sample in the application space after
 * confirmation.
 */
sealed interface PtoCeiling {
    /** quiche's own PTO, uncapped. */
    data object None : PtoCeiling

    /** A fixed ceiling. */
    class At(
        val nanos: Long,
    ) : PtoCeiling

    /**
     * A ceiling from [firstNanos], times [growth] with each PTO expiry up to [maxNanos], and back to [firstNanos] when
     * quiche discards a packet number space's keys.
     */
    class Growing(
        val firstNanos: Long,
        val growth: Int,
        val maxNanos: Long,
    ) : PtoCeiling
}

/** [ceiling]'s first value as the C seam's argument: nanoseconds, zero for none. */
internal fun ptoCeilingFirstNanos(ceiling: PtoCeiling): Long =
    when (ceiling) {
        is PtoCeiling.None -> 0L
        is PtoCeiling.At -> ceiling.nanos
        is PtoCeiling.Growing -> ceiling.firstNanos
    }

/** [ceiling]'s factor per PTO expiry as the C seam's argument. */
internal fun ptoCeilingGrowth(ceiling: PtoCeiling): Int =
    when (ceiling) {
        is PtoCeiling.None, is PtoCeiling.At -> 1
        is PtoCeiling.Growing -> ceiling.growth
    }

/** [ceiling]'s largest value as the C seam's argument, in nanoseconds. */
internal fun ptoCeilingMaxNanos(ceiling: PtoCeiling): Long =
    when (ceiling) {
        is PtoCeiling.None -> 0L
        is PtoCeiling.At -> ceiling.nanos
        is PtoCeiling.Growing -> ceiling.maxNanos
    }
