package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.core.life.CloseRule
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

/** Whether a server accepts a resuming client's 0-RTT. A client's offer is its own connection's. */
internal enum class EarlyDataRule { Refused, Accepted }

internal sealed interface AckRule {
    data object Immediate : AckRule
}

/** The rules every connection of an endpoint is built with; immutable and shared. */
internal class SlotRules(
    val pacing: PacingRule,
    val ack: AckRule,
    val handshake: HandshakeRule,
    val close: CloseRule,
    val earlyData: EarlyDataRule = EarlyDataRule.Refused,
) {
    companion object {
        /**
         * A loop whose waits wake within a millisecond, quiche's own RFC 9002 handshake with no deadline, and a graceful
         * close that waits for what was written to be acknowledged, at most the default idle timeout.
         */
        val Default =
            forWaits(
                WaitResolution(1_000_000L),
                HandshakeRule(PtoRule.Rfc9002, HandshakeBound.UntilClosed, LinkClass.Unknown),
                CloseRule.UntilAcknowledged(DEFAULT_IDLE_NANOS),
            )

        /**
         * quiche's pacing within one step of the loop's wait is sent at once: holding it would sleep a whole step
         * (16 ms on Windows) for what is due sooner. Beyond a second it is a different clock's reading.
         */
        fun forWaits(
            waits: WaitResolution,
            handshake: HandshakeRule,
            close: CloseRule,
            earlyData: EarlyDataRule = EarlyDataRule.Refused,
        ) = SlotRules(
            PacingRule.ByQuiche(allowanceNanos = waits.nanos, limitNanos = 1_000_000_000L),
            AckRule.Immediate,
            handshake,
            close,
            earlyData,
        )

        private const val DEFAULT_IDLE_NANOS = 30_000_000_000L
    }
}
