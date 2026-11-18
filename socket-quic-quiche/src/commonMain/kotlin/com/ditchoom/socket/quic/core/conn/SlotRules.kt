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

/**
 * When a connection writes the ACK quiche owes. Every turn writes at most one: quiche puts an ACK in the first packet a
 * write sends after an ack-eliciting packet arrived, and a turn writes once, after every datagram of its pass was read.
 */
internal sealed interface AckRule {
    /** Every turn that received writes its ACK. */
    data object Immediate : AckRule

    /**
     * A turn that handed received data to the application holds its ACK for the reply, at most [maxNanos] and at most
     * the smoothed RTT divided by [rttShare] ([AckHold]).
     */
    class HoldForReply(
        val maxNanos: Long,
        val rttShare: Int,
    ) : AckRule

    companion object {
        /**
         * The max_ack_delay every connection advertises: quiche's default, as no config sets it. A hold's deadline may
         * be served one wait step late, so a hold lasts at most this less [waits].
         */
        const val ADVERTISED_MAX_ACK_DELAY_NANOS = 25_000_000L

        /** A hold lasts at most this share of the smoothed RTT: the peer's samples move by little. */
        const val RTT_SHARE = 4

        fun holdForReply(waits: WaitResolution): AckRule = HoldForReply(ADVERTISED_MAX_ACK_DELAY_NANOS - waits.nanos, RTT_SHARE)
    }
}

/** Whether a connection carries RFC 9221 datagrams. */
internal sealed interface DatagramRule {
    data object Off : DatagramRule

    /** Datagrams that arrive while no receive waits are kept, [bound] at most, the oldest dropped first. */
    class On(
        val bound: Int,
    ) : DatagramRule
}

/** Whether an idle connection is pinged. */
internal sealed interface KeepAliveRule {
    data object Off : KeepAliveRule

    /** After [intervalNanos] with nothing received or sent. */
    class Every(
        val intervalNanos: Long,
    ) : KeepAliveRule
}

/** The rules every connection of an endpoint is built with; immutable and shared. */
internal class SlotRules(
    val pacing: PacingRule,
    val ack: AckRule,
    val handshake: HandshakeRule,
    val close: CloseRule,
    val datagrams: DatagramRule = DatagramRule.Off,
    val keepAlive: KeepAliveRule = KeepAliveRule.Off,
    val earlyData: EarlyDataRule = EarlyDataRule.Refused,
) {
    companion object {
        /**
         * A loop whose waits wake within a millisecond, quiche's own RFC 9002 handshake with no deadline, a graceful
         * close that waits for what was written to be acknowledged, at most the default idle timeout, and ACKs held for
         * a likely reply.
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
            datagrams: DatagramRule = DatagramRule.Off,
            keepAlive: KeepAliveRule = KeepAliveRule.Off,
            earlyData: EarlyDataRule = EarlyDataRule.Refused,
        ) = SlotRules(
            PacingRule.ByQuiche(allowanceNanos = waits.nanos, limitNanos = 1_000_000_000L),
            AckRule.holdForReply(waits),
            handshake,
            close,
            datagrams,
            keepAlive,
            earlyData,
        )

        private const val DEFAULT_IDLE_NANOS = 30_000_000_000L
    }
}
