package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicheClockPin
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.LoopPin
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.QuicheTime

/** Where a connection's reads inside come from, chosen once from its loop's [QuicheTime]. */
internal sealed interface TurnTime {
    /** libquiche reads the instant whoever entered held, so every read inside is that instant and none touches the clock. */
    class Held(
        val pin: QuicheClockPin,
        val loop: LoopPin,
    ) : TurnTime

    /** libquiche reads its own clock: reads inside stay live, so they stay comparable with its stamps. */
    data object Live : TurnTime

    companion object {
        /** [Held] when the loop pins libquiche and [clock] counts on its base; [Live] otherwise. */
        fun of(
            time: QuicheTime,
            clock: LoopClock,
        ): TurnTime =
            when (time) {
                is QuicheTime.Own -> Live
                is QuicheTime.Pinned ->
                    when (clock.base()) {
                        is ClockBase.Libquiche -> Held(time.pin, time.loop)
                        is ClockBase.Own -> Live
                    }
            }
    }
}

/**
 * The time a connection reads while someone is inside it, and its [live] clock. Whoever enters says when: the loop its
 * pass's instant, a caller its own reading. Confined to whoever is inside.
 */
@Confined
internal class TurnClock(
    private val time: TurnTime,
    private val clock: LoopClock,
) : LoopClock {
    private var at = Nanos(0L)

    override fun now(): Nanos =
        when (time) {
            is TurnTime.Held -> at
            is TurnTime.Live -> clock.now()
        }

    override fun base(): ClockBase = clock.base()

    /** A reading of the clock itself, for whoever has not entered. */
    fun live(): Nanos = clock.now()

    /**
     * The loop enters at its pass's instant [at], which it pinned libquiche to, or at the later one a caller last served
     * the connection at, so its time never runs backwards; returns the instant held.
     */
    fun held(at: Nanos): Nanos {
        if (at > this.at) this.at = at
        when (time) {
            is TurnTime.Held -> time.loop.to(this.at)
            is TurnTime.Live -> Unit
        }
        return this.at
    }

    /** A caller enters at [at], its own reading; libquiche reads it too until [unpinHere]. */
    fun pinHere(at: Nanos) {
        if (at > this.at) this.at = at
        when (time) {
            is TurnTime.Held -> time.pin.pinMonotonic(this.at.value)
            is TurnTime.Live -> Unit
        }
    }

    /** The caller left: libquiche reads its own clock again on the caller's thread. */
    fun unpinHere() =
        when (time) {
            is TurnTime.Held -> time.pin.unpin()
            is TurnTime.Live -> Unit
        }
}
