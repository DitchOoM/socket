package com.ditchoom.socket.quic.core.time

import com.ditchoom.socket.quic.core.observe.LoopOnly

/** Whether anything asked for a wake since the last [WakeSink.reset]. */
internal enum class WakeState { Asked, NotAsked }

private sealed interface Asked {
    data object Nothing : Asked

    class Earliest : Asked {
        var atNanos = 0L
    }
}

/**
 * The earliest time a connection must be served again. Owners offer the times they need; "no time" is no call to
 * [offer], so no value stands for it.
 */
@LoopOnly
internal class WakeSink {
    private val earliest = Asked.Earliest()
    private var asked: Asked = Asked.Nothing

    fun offer(at: Nanos) {
        when (val now = asked) {
            is Asked.Nothing -> {
                earliest.atNanos = at.value
                asked = earliest
            }
            is Asked.Earliest -> if (at.value < now.atNanos) now.atNanos = at.value
        }
    }

    fun state(): WakeState =
        when (asked) {
            is Asked.Nothing -> WakeState.NotAsked
            is Asked.Earliest -> WakeState.Asked
        }

    /** The earliest offered time; meaningful only when [state] is [WakeState.Asked]. */
    fun earliest(): Nanos = Nanos(earliest.atNanos)

    fun reset() {
        asked = Asked.Nothing
    }
}
