package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

internal enum class PaceState { Free, Holding }

/**
 * How the pacer judged a datagram's due time against the loop's clock. Each case is an object, so judging allocates
 * nothing; how far ahead the datagram was due is [Pacer.aheadNanos].
 */
internal sealed interface Due {
    /** Due within the allowance: send it. */
    data object Now : Due

    /** Due later than the allowance, within a pacing interval's reach: hold the connection's sending until then. */
    data object Hold : Due

    /**
     * Due further ahead than a pacing interval can be: quiche stamped it on a clock the loop does not read. Pacing cannot be
     * kept; the connection answers as its [MismatchResponse] says.
     */
    data object ClockMismatch : Due
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
 * no datagram after it does. A time further ahead than the limit is not pacing: it is a [Due.ClockMismatch].
 */
@Confined
internal class Pacer(
    private val rule: PacingRule.ByQuiche,
) {
    private val holding = Pace.Holding()
    private var phase: Pace = Pace.Free
    private var ahead = 0L

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
        if (ahead > rule.limitNanos) return Due.ClockMismatch
        holding.untilNanos = atNanos
        phase = holding
        return Due.Hold
    }

    /** How far ahead of the loop's clock the datagram of the last [sent] was due; meaningful for [Due.Hold] and [Due.ClockMismatch]. */
    fun aheadNanos(): Long = ahead

    /** Offers the hold's time while holding; offers nothing otherwise. */
    fun offerWake(sink: WakeSink) =
        when (val p = phase) {
            is Pace.Free -> Unit
            is Pace.Holding -> sink.offer(Nanos(p.untilNanos))
        }
}
