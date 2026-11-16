package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.time.WaitResolution

/** Whether a connection obeys the send times quiche's pacer gives each datagram. */
internal sealed interface PacingRule {
    data object Unpaced : PacingRule

    /** A datagram due within [allowanceNanos] goes now; a time further ahead than [limitNanos] is not pacing. */
    class ByQuiche(
        val allowanceNanos: Long,
        val limitNanos: Long,
    ) : PacingRule
}

internal sealed interface AckRule {
    data object Immediate : AckRule
}

/** The rules every connection of an endpoint is built with; immutable and shared. */
internal class SlotRules(
    val pacing: PacingRule,
    val ack: AckRule,
) {
    companion object {
        /** A loop whose waits wake within a millisecond. */
        val Default = forWaits(WaitResolution(1_000_000L))

        /**
         * quiche's pacing within one step of the loop's wait is sent at once: holding it would sleep a whole step
         * (16 ms on Windows) for what is due sooner. Beyond a second it is a different clock's reading.
         */
        fun forWaits(waits: WaitResolution) =
            SlotRules(
                PacingRule.ByQuiche(allowanceNanos = waits.nanos, limitNanos = 1_000_000_000L),
                AckRule.Immediate,
            )
    }
}
