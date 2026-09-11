package com.ditchoom.socket.testkit.trace

import kotlin.time.Duration

/**
 * The byte budget a walk may spend on its replay traces, derived from the run's own duration and
 * echo cadence rather than fixed: a constant sized for one cadence is silently wrong for every
 * other, and the tail of a multi-day run is exactly where the handoff worth replaying lands.
 *
 * Doubled for the events that do not scale with the echo loop (path changes, reconnects,
 * heartbeats), floored so a short run still has room to record something, and capped so a
 * reconnect storm still cannot fill the device, which is what a budget is for.
 */
public data class TraceBudget(
    val bytes: Long,
    val plannedExchanges: Long,
) {
    public val megabytes: Long get() = bytes / MEBIBYTE

    /** The log line, in the grammar the walk logs are grepped for. */
    public val line: String get() = "TRACE-BUDGET mb=$megabytes plannedExchanges=$plannedExchanges"

    /** The same plan with an operator-chosen ceiling, for a run that wants a different one. */
    public fun withMegabytes(megabytes: Long): TraceBudget = copy(bytes = megabytes * MEBIBYTE)

    public companion object {
        /**
         * Bytes of v1 trace one echo exchange produces at a 250 ms cadence, measured on device:
         * 574 on a Samsung SM-F956U1 (275,722 bytes over 480 exchanges) and 559 on an iPhone 16
         * Pro Max (201,379,680 bytes over 25 h).
         */
        public const val BYTES_PER_EXCHANGE: Long = 574L
        public const val FLOOR_MB: Long = 512L
        public const val CEILING_MB: Long = 4_096L
        private const val MEBIBYTE: Long = 1024L * 1024L

        public fun forWalk(
            minutes: Int,
            echoInterval: Duration,
        ): TraceBudget {
            val plannedExchanges = minutes.toLong() * 60_000L / echoInterval.inWholeMilliseconds
            val megabytes = (plannedExchanges * BYTES_PER_EXCHANGE * 2 / MEBIBYTE).coerceIn(FLOOR_MB, CEILING_MB)
            return TraceBudget(bytes = megabytes * MEBIBYTE, plannedExchanges = plannedExchanges)
        }
    }
}
