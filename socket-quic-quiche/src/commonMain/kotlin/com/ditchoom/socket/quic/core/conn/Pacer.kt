package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

internal enum class PaceState { Free, Holding }

/** How the pacer judged a datagram's due time against the loop's clock; how far ahead it was is [Pacer.aheadNanos]. */
internal sealed interface Due {
    /** Due within the allowance: send it. */
    data object Now : Due

    /** Due later than the allowance, however much later: hold the connection's sending until then. */
    data object Hold : Due
}

/**
 * Whether libquiche stamps send times on the loop's clock, judged once per connection. Never a time limit: a send time
 * on the same clock is pacing however far ahead it is.
 */
internal sealed interface ClockVerdict {
    /** No datagram written yet. */
    data object Unjudged : ClockVerdict

    /** libquiche reads the instant the loop pinned it to: the same clock by construction, so nothing is judged. */
    data object Pinned : ClockVerdict

    /** The first datagram was stamped within the loop's readings around its write. */
    data object Agrees : ClockVerdict

    /** The first datagram was stamped [offsetNanos] off the loop's readings around its write: ahead when positive, behind when negative. */
    class Differs(
        val offsetNanos: Long,
    ) : ClockVerdict
}

private sealed interface Pace {
    data object Free : Pace

    class Holding : Pace {
        var untilNanos = 0L
    }
}

/**
 * The send hold quiche's pacer asks for. A datagram due later than the allowance stops the write and holds the
 * connection's sending until then; that datagram is already written, so it leaves up to one pacing interval early, and
 * no datagram after it does. Whether the times are on the loop's clock at all is the [ClockVerdict] of the connection's
 * first datagram ([judge]).
 */
@Confined
internal class Pacer(
    private val rule: PacingRule.ByQuiche,
    first: ClockVerdict,
) {
    private val holding = Pace.Holding()
    private var phase: Pace = Pace.Free
    private var ahead = 0L
    private var verdict = first

    /** The connection's clock verdict so far. */
    fun verdict(): ClockVerdict = verdict

    /**
     * Judges the clocks by the connection's first datagram, stamped [atNanos], written between the loop's readings
     * [before] and [after]. quiche stamps a datagram `max(now, its pacer's release)`, and before any datagram its pacer
     * releases at once (`ReleaseTime::Immediate`; CUBIC and Reno always stamp now), so the first stamp is quiche's own
     * now, read during that write. On one clock it lies between the readings; [CLOCK_TOLERANCE_NANOS] only covers units.
     */
    fun judge(
        atNanos: Long,
        before: Nanos,
        after: Nanos,
    ): ClockVerdict {
        verdict =
            when {
                atNanos < before.value - CLOCK_TOLERANCE_NANOS -> ClockVerdict.Differs(atNanos - before.value)
                atNanos > after.value + CLOCK_TOLERANCE_NANOS -> ClockVerdict.Differs(atNanos - after.value)
                else -> ClockVerdict.Agrees
            }
        return verdict
    }

    /** Whether the connection may write at [now]; a hold due within the allowance is released. */
    fun state(now: Nanos): PaceState =
        when (val p = phase) {
            is Pace.Free -> PaceState.Free
            is Pace.Holding ->
                if (p.untilNanos - now.value > rule.allowanceNanos) {
                    PaceState.Holding
                } else {
                    phase = Pace.Free
                    PaceState.Free
                }
        }

    /** A datagram quiche wants on the wire at [atNanos] was written at [now]. */
    fun sent(
        atNanos: Long,
        now: Nanos,
    ): Due {
        ahead = atNanos - now.value
        if (ahead <= rule.allowanceNanos) return Due.Now
        holding.untilNanos = atNanos
        phase = holding
        return Due.Hold
    }

    /** What the connection answers a [ClockVerdict.Differs] with. */
    fun response(): MismatchResponse = rule.mismatch

    /** How far ahead of the loop's clock the datagram of the last [sent] was due; meaningful for [Due.Hold]. */
    fun aheadNanos(): Long = ahead

    /** Offers the hold's time while holding; offers nothing otherwise. */
    fun offerWake(sink: WakeSink) =
        when (val p = phase) {
            is Pace.Free -> Unit
            is Pace.Holding -> sink.offer(Nanos(p.untilNanos))
        }

    companion object {
        /**
         * How far a first stamp may fall outside the loop's readings around its write and still be the loop's clock. The
         * readings bracket quiche's own read, so a stall anywhere in the write widens the bracket, not the error. What is
         * left is units: the Apple and Windows stamp patches scale the host counter to nanoseconds as the runtime does,
         * nanoseconds apart. A millisecond is far above that and far below another clock's error: another origin is hours
         * off, another unit a thousand-fold, an unconverted stamp the whole uptime.
         */
        const val CLOCK_TOLERANCE_NANOS = 1_000_000L
    }
}
