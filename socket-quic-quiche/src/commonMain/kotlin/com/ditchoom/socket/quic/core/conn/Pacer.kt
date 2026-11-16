package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

internal enum class PaceState { Free, Holding }

private sealed interface Pace {
    data object Free : Pace

    class Holding : Pace {
        var untilNanos = 0L
    }
}

/**
 * The send hold quiche's pacer asks for. A datagram due later than the allowance stops the write and holds the
 * connection's sending until then; that datagram is already written, so it leaves up to one pacing interval early, and
 * no datagram after it does. A time further ahead than the limit is not pacing (a clock libquiche reads that the loop
 * does not) and is not obeyed.
 */
@Confined
internal class Pacer(
    private val rule: PacingRule.ByQuiche,
) {
    private val holding = Pace.Holding()
    private var phase: Pace = Pace.Free

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
    ): PaceState {
        val ahead = atNanos - now.value
        if (ahead <= rule.allowanceNanos || ahead > rule.limitNanos) return PaceState.Free
        holding.untilNanos = atNanos
        phase = holding
        return PaceState.Holding
    }

    /** Offers the hold's time while holding; offers nothing otherwise. */
    fun offerWake(sink: WakeSink) =
        when (val p = phase) {
            is Pace.Free -> Unit
            is Pace.Holding -> sink.offer(Nanos(p.untilNanos))
        }
}
