package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.core.life.CloseRule
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.time.since
import kotlin.jvm.JvmInline

/** Whether a connection obeys the send times quiche's pacer gives each datagram. */
internal sealed interface PacingRule {
    data object Unpaced : PacingRule

    /**
     * A datagram due within [allowanceNanos] goes now and a later one is held for, however much later. A connection whose
     * first datagram finds libquiche on another clock ([ClockVerdict.Differs]) answers as [mismatch] says.
     */
    class ByQuiche(
        val allowanceNanos: Long,
        val mismatch: MismatchResponse,
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
     * A turn that handed received data to the application lets its ACK wait until the connection leaves the current
     * loop pass ([SamePassHold]): a reply queued in that pass carries it, and otherwise it leaves at the end of the pass.
     */
    data object UntilPassEnds : AckRule

    /**
     * A turn that handed received data to the application holds its ACK across turns for the reply, at most [cap] and
     * at most the smoothed RTT divided by [rttShare] ([AckHold]).
     */
    class AcrossTurns(
        val cap: AckDelayCap,
        val rttShare: Int,
    ) : AckRule

    companion object {
        /** The max_ack_delay every connection advertises: quiche's default, as no config sets it. */
        val ADVERTISED_MAX_ACK_DELAY = MaxAckDelay(25_000_000L)

        /** A hold across turns lasts at most this share of the smoothed RTT: the peer's samples move by little. */
        const val RTT_SHARE = 4

        fun acrossTurns(waits: WaitResolution): AckRule = AcrossTurns(AckDelayCap.within(ADVERTISED_MAX_ACK_DELAY, waits), RTT_SHARE)
    }
}

/** The max_ack_delay a connection advertises (RFC 9000 §18.2), in nanoseconds. */
@JvmInline
internal value class MaxAckDelay(
    val nanos: Long,
)

/** The longest an ACK may be held across turns: only made from the advertised max_ack_delay. */
@JvmInline
internal value class AckDelayCap private constructor(
    val nanos: Long,
) {
    companion object {
        /** The advertised [delay] less one wait step: a hold's deadline may be served one step late. */
        fun within(
            delay: MaxAckDelay,
            waits: WaitResolution,
        ): AckDelayCap = AckDelayCap(maxOf(delay.nanos - waits.nanos, 0L))
    }
}

/** Whether a connection carries RFC 9221 datagrams. */
internal sealed interface DatagramRule {
    data object Off : DatagramRule

    /**
     * Datagrams that arrive while no receive waits are kept, [bound] at most, the oldest dropped first, and dropped
     * undelivered past [incoming]. Up to [sendBound] wait to be sent; a send past it goes as [overflow] says, and one
     * older than [outgoing] at the head of the queue is dropped.
     */
    class On(
        val bound: Int,
        val incoming: AgeBound,
        val sendBound: Int,
        val overflow: Overflow,
        val outgoing: AgeBound,
    ) : DatagramRule
}

/**
 * The datagrams quiche's own send queue holds for its next packets: enough to fill one with small datagrams, or a few
 * when eviction or an outgoing age must reach nearly every queued datagram; never more than half of [DatagramRule.On.sendBound].
 */
internal fun DatagramRule.On.quicheHandOff(): Int {
    val wanted =
        when (overflow) {
            Overflow.EvictOldest -> SHORT_HAND_OFF
            Overflow.RefuseNew ->
                when (outgoing) {
                    is AgeBound.Within -> SHORT_HAND_OFF
                    is AgeBound.Unbounded -> LONG_HAND_OFF
                }
        }
    return minOf(wanted, sendBound / 2).coerceAtLeast(1)
}

/**
 * The datagrams the core's queue admits: the depth less quiche's hand-off and the one datagram the connection holds at
 * the door while quiche's queue is full, so together they hold at most [DatagramRule.On.sendBound]. A depth below
 * [MIN_SEND_DEPTH] leaves none, and is refused as an option.
 */
internal fun DatagramRule.On.queueBound(): Int = sendBound - quicheHandOff() - DOOR

/** The smallest send depth: one datagram in quiche's hand-off, one at the door, and one in the core's queue. */
internal const val MIN_SEND_DEPTH = 3

private const val DOOR = 1

private const val SHORT_HAND_OFF = 4
private const val LONG_HAND_OFF = 32

/** What a full datagram send queue does with one more. */
internal enum class Overflow { RefuseNew, EvictOldest }

/** How long a queued datagram stays worth delivering. */
internal sealed interface AgeBound {
    data object Unbounded : AgeBound

    /** One queued longer than [age] is dropped when it reaches the head of its queue. */
    class Within(
        val age: Span,
    ) : AgeBound
}

/** Whether a datagram queued at [queuedAt] is past this bound at [now]. */
internal fun AgeBound.expired(
    queuedAt: Nanos,
    now: Nanos,
): Expiry =
    when (this) {
        is AgeBound.Unbounded -> Expiry.Fresh
        is AgeBound.Within -> if (now.since(queuedAt) > age) Expiry.Expired else Expiry.Fresh
    }

internal enum class Expiry { Fresh, Expired }

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
         * close that waits for what was written to be acknowledged, at most the default idle timeout, and an ACK that
         * waits until the end of its pass for a reply queued in it.
         */
        val Default =
            forWaits(
                WaitResolution(1_000_000L),
                HandshakeRule(PtoRule.Rfc9002, HandshakeBound.UntilClosed, LinkClass.Unknown),
                CloseRule.UntilAcknowledged(DEFAULT_IDLE_NANOS),
            )

        /**
         * quiche's pacing within one step of the loop's wait is sent at once: holding it would sleep a whole step
         * (16 ms on Windows) for what is due sooner. A connection on a clock of its own is not paced,
         * libquiche's times not being on it; [buildConn] reads that off the connection's clock.
         */
        fun forWaits(
            waits: WaitResolution,
            handshake: HandshakeRule,
            close: CloseRule,
            datagrams: DatagramRule = DatagramRule.Off,
            keepAlive: KeepAliveRule = KeepAliveRule.Off,
            earlyData: EarlyDataRule = EarlyDataRule.Refused,
            ack: AckRule = AckRule.UntilPassEnds,
            mismatch: MismatchResponse = MismatchResponse.Unpaced,
        ) = SlotRules(
            PacingRule.ByQuiche(allowanceNanos = waits.nanos, mismatch = mismatch),
            ack,
            handshake,
            close,
            datagrams,
            keepAlive,
            earlyData,
        )

        private const val DEFAULT_IDLE_NANOS = 30_000_000_000L
    }
}
