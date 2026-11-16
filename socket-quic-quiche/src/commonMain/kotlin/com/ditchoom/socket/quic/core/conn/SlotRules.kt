package com.ditchoom.socket.quic.core.conn

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
        /** quiche's pacing within 1 ms is sent at once; beyond a second it is a different clock's reading. */
        val Default =
            SlotRules(
                PacingRule.ByQuiche(allowanceNanos = 1_000_000L, limitNanos = 1_000_000_000L),
                AckRule.Immediate,
            )
    }
}
