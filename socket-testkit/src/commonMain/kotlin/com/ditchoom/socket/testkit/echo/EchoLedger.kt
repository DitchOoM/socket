package com.ditchoom.socket.testkit.echo

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * RFC 9002 §5.3 smoothed round-trip estimate, fed by the exchanges a probe itself completes.
 *
 * Sealed on whether a sample has arrived. Before the first one the RFC's `kInitialRtt` stands in,
 * exactly as it does for the transport's own timers, so "no estimate yet" is a case and not a field
 * that has to be read with care.
 */
public sealed interface RttEstimate {
    public val smoothed: Duration
    public val variance: Duration

    /**
     * RFC 9002 §6.2.1 probe timeout: `smoothed_rtt + max(4·rttvar, kGranularity) + max_ack_delay`,
     * the wait after which the transport itself stops expecting an answer on this path. An exchange
     * that takes longer has, by the path's own reckoning, outlived a probe timeout.
     */
    public val probeTimeout: Duration
        get() = smoothed + maxOf(variance * 4, GRANULARITY) + MAX_ACK_DELAY

    public fun with(sample: Duration): Sampled

    /** No exchange has completed: `kInitialRtt` with the RFC's `initial_rtt / 2` variance. */
    public data object Unsampled : RttEstimate {
        override val smoothed: Duration = INITIAL_RTT
        override val variance: Duration = INITIAL_RTT / 2

        override fun with(sample: Duration): Sampled = Sampled(smoothed = sample, variance = sample / 2)
    }

    public data class Sampled(
        override val smoothed: Duration,
        override val variance: Duration,
    ) : RttEstimate {
        override fun with(sample: Duration): Sampled =
            Sampled(
                variance = variance * 3 / 4 + (smoothed - sample).absoluteValue / 4,
                smoothed = smoothed * 7 / 8 + sample / 8,
            )
    }

    public companion object {
        public val INITIAL_RTT: Duration = 333.milliseconds
        public val GRANULARITY: Duration = 1.milliseconds

        /**
         * RFC 9000 §18.2 default `max_ack_delay`. The peer's negotiated value is not visible to a
         * probe; the default is what the echo server advertises.
         */
        public val MAX_ACK_DELAY: Duration = 25.milliseconds
    }
}

/** What one exchange turned out to be, decided when its reply arrived and never at a deadline. */
public sealed interface EchoOutcome {
    public val seq: Int
    public val roundTrip: Duration

    /** The log line, in the grammar `analyze.py` and `status.sh` read; [owedBytes] is what is still unechoed after this read. */
    public fun line(owedBytes: Int): String

    public data class OnTime(
        override val seq: Int,
        override val roundTrip: Duration,
    ) : EchoOutcome {
        override fun line(owedBytes: Int): String = "ECHO-OK seq=$seq rtt=${roundTrip.inWholeMilliseconds}ms pending=${owedBytes}B"
    }

    /** The reply came back after the deadline in force when it was sent. Late, and nothing more: the bytes arrived. */
    public data class Late(
        override val seq: Int,
        override val roundTrip: Duration,
        val deadline: Duration,
    ) : EchoOutcome {
        override fun line(owedBytes: Int): String =
            "ECHO-LATE seq=$seq rtt=${roundTrip.inWholeMilliseconds}ms " +
                "late=+${(roundTrip - deadline).inWholeMilliseconds}ms deadline=${deadline.inWholeMilliseconds}ms pending=${owedBytes}B"
    }
}

/** What one read of the stream did to the ledger. */
public sealed interface EchoRead {
    /** The bytes were the head of what is owed; every payload they completed has its outcome. */
    public data class Consumed(
        val outcomes: List<EchoOutcome>,
        val owedBytes: Int,
    ) : EchoRead

    /** The bytes are not a prefix of what is owed: the stream delivered something that was never sent. */
    public data class Diverged(
        val atByte: Int,
        val expected: String,
        val received: String,
    ) : EchoRead {
        public val detail: String get() = "atByte=$atByte expected=[$expected] recv=[$received]"
    }
}

/** An exchange whose reply is now older than its deadline and still owed. Reported once, when it crosses. */
public data class EchoOverdue(
    val seq: Int,
    val waited: Duration,
    val deadline: Duration,
) {
    public val line: String
        get() = "ECHO-OVERDUE seq=$seq waited=${waited.inWholeMilliseconds}ms deadline=${deadline.inWholeMilliseconds}ms"
}

/** What was still owed when the connection ended: the exchanges that actually failed. */
public sealed interface EchoUnanswered {
    public data object None : EchoUnanswered

    public data class Some(
        val count: Int,
        val firstSeq: Int,
        val lastSeq: Int,
    ) : EchoUnanswered {
        public val line: String get() = "ECHO-UNANSWERED count=$count first=$firstSeq last=$lastSeq"
    }
}

/**
 * The bytes a probe has sent and not yet seen echoed, and what each exchange turned out to be.
 *
 * An echo rides a reliable, ordered stream, so while the connection lives a reply cannot be lost,
 * only late. The ledger therefore decides an exchange when its reply arrives, never when a read
 * deadline passes: a missed deadline leaves the exchange open (and, once past its own bound,
 * [overdue]), and the reply that eventually comes back is [EchoOutcome.Late] with its real round
 * trip. Every payload keeps its own send time, so a read that returns several coalesced echoes
 * gives each its own round trip instead of the last one's.
 *
 * The invariant it checks is that everything received is an exact, in-order prefix of everything
 * sent. Late delivery keeps that true; bytes destroyed by a timed-out read break it permanently.
 * Only the unechoed tail is kept, so a multi-day run costs O(echo) per read, not O(history).
 *
 * The deadline to read with is [readDeadline]: the probe timeout of the path this ledger measures.
 * Owned by one echo loop; not shared.
 */
public class EchoLedger(
    rtt: RttEstimate = RttEstimate.Unsampled,
) {
    public var rtt: RttEstimate = rtt
        private set

    private enum class Expectation { Due, Overdue }

    private data class Sent(
        val seq: Int,
        val bytes: Int,
        val sentAt: Duration,
        val deadline: Duration,
        val expectation: Expectation,
    )

    private val pending = ArrayDeque<Sent>()
    private val owed = StringBuilder()

    /** Bytes of the head payload that an earlier, partial read already echoed. */
    private var headEchoed = 0

    public val readDeadline: Duration get() = rtt.probeTimeout
    public val owedBytes: Int get() = owed.length

    public fun sent(
        seq: Int,
        payload: String,
        at: Duration,
    ) {
        pending.addLast(Sent(seq, payload.length, at, readDeadline, Expectation.Due))
        owed.append(payload)
    }

    public fun received(
        echoed: String,
        at: Duration,
    ): EchoRead {
        val prefix = echoed.length <= owed.length && owed.regionMatches(0, echoed, 0, echoed.length)
        if (!prefix) {
            val atByte = echoed.indices.firstOrNull { it >= owed.length || owed[it] != echoed[it] } ?: 0
            return EchoRead.Diverged(
                atByte = atByte,
                expected = owed.substring(0, minOf(owed.length, atByte + 24)),
                received = echoed.substring(0, minOf(echoed.length, atByte + 24)),
            )
        }
        owed.deleteRange(0, echoed.length)
        var covered = headEchoed + echoed.length
        val outcomes = ArrayList<EchoOutcome>()
        while (pending.isNotEmpty() && pending.first().bytes <= covered) {
            val done = pending.removeFirst()
            covered -= done.bytes
            val roundTrip = at - done.sentAt
            rtt = rtt.with(roundTrip)
            outcomes +=
                if (roundTrip <= done.deadline) {
                    EchoOutcome.OnTime(done.seq, roundTrip)
                } else {
                    EchoOutcome.Late(done.seq, roundTrip, done.deadline)
                }
        }
        headEchoed = covered
        return EchoRead.Consumed(outcomes, owed.length)
    }

    /** The exchanges that crossed their own deadline since the last call, oldest first. */
    public fun overdue(at: Duration): List<EchoOverdue> {
        val crossed = ArrayList<EchoOverdue>()
        for (i in pending.indices) {
            val sent = pending[i]
            if (sent.expectation == Expectation.Due && at - sent.sentAt > sent.deadline) {
                pending[i] = sent.copy(expectation = Expectation.Overdue)
                crossed += EchoOverdue(sent.seq, at - sent.sentAt, sent.deadline)
            }
        }
        return crossed
    }

    /** Close the ledger at the end of a connection: whatever is still owed is what failed. */
    public fun abandon(): EchoUnanswered {
        val unanswered =
            if (pending.isEmpty()) {
                EchoUnanswered.None
            } else {
                EchoUnanswered.Some(pending.size, pending.first().seq, pending.last().seq)
            }
        pending.clear()
        owed.clear()
        headEchoed = 0
        return unanswered
    }
}
