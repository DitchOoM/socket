package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

/** What the network monitor said, as a connection is told it. */
internal enum class NetworkChange {
    /** A usable network appeared where there was none. */
    Usable,

    /** The usable network changed to another one. */
    Changed,

    /** No usable network at all. */
    Gone,

    /** A usable network, without the local address the connection's socket is bound to. */
    Moved,
}

/** What a connection does about a change. */
internal enum class NetworkStep {
    /** Have quiche send at once: what its backed-off timers hold back is recovered in about one round trip. */
    Nudge,

    /** Keep going until the grace passes. */
    Wait,

    /** The connection cannot go on from its socket: it ends now. */
    End,
}

internal enum class NetworkVerdict { Usable, Lost }

private sealed interface Watch {
    data object Usable : Watch

    class Gone : Watch {
        var untilNanos = 0L
    }
}

/**
 * Whether the connection still has a usable network. A report of none starts the grace a flap is given; a usable
 * network reported within it ends the grace, and when it passes the connection ends. Migration replaces the grace.
 */
@Confined
internal class NetworkWatch(
    private val rule: NetworkRule,
) {
    private val gone = Watch.Gone()
    private var phase: Watch = Watch.Usable

    fun changed(
        change: NetworkChange,
        now: Nanos,
    ): NetworkStep =
        when (change) {
            NetworkChange.Usable, NetworkChange.Changed -> {
                phase = Watch.Usable
                NetworkStep.Nudge
            }
            NetworkChange.Gone -> {
                if (phase is Watch.Usable) {
                    gone.untilNanos = now.value + rule.graceNanos
                    phase = gone
                }
                NetworkStep.Wait
            }
            NetworkChange.Moved -> NetworkStep.End
        }

    fun verdict(now: Nanos): NetworkVerdict =
        when (val p = phase) {
            is Watch.Usable -> NetworkVerdict.Usable
            is Watch.Gone -> if (now.value >= p.untilNanos) NetworkVerdict.Lost else NetworkVerdict.Usable
        }

    fun offerWake(sink: WakeSink) =
        when (val p = phase) {
            is Watch.Usable -> Unit
            is Watch.Gone -> sink.offer(Nanos(p.untilNanos))
        }
}
