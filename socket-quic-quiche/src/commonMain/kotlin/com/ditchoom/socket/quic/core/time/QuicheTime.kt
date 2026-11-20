package com.ditchoom.socket.quic.core.time

import com.ditchoom.socket.quic.PinAnswer
import com.ditchoom.socket.quic.PinLookup
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheClockPin
import com.ditchoom.socket.quic.QuicheClockPins

/**
 * Whose clock libquiche reads on a loop, chosen once when the loop is made. A clock read is a system call on some
 * kernels, and on a guest whose clocksource is HPET (the Android 9 and 10 emulators) a trap to the host of about
 * 13 µs; libquiche alone read it some 30 times per echo.
 */
internal sealed interface QuicheTime {
    /** libquiche reads its own clock: whatever the loop's connections compare with its stamps is read live. */
    data object Own : QuicheTime

    /** libquiche reads the instant the loop read for its pass: one reading serves the pass, quiche's and the core's. */
    class Pinned(
        val pin: QuicheClockPin,
    ) : QuicheTime

    companion object {
        /**
         * [Pinned] when [clock] counts on libquiche's base and [api]'s backend pins it here, probed once on this
         * thread and released; [Own] otherwise: a virtual clock, a decorated api, or a platform that cannot pin.
         */
        fun of(
            api: QuicheApi,
            clock: LoopClock,
        ): QuicheTime {
            if (clock.base() !is ClockBase.Libquiche) return Own
            val pin =
                when (val bound = QuicheClockPins.of(api)) {
                    is PinLookup.Bound -> bound.pin
                    is PinLookup.NotBound -> return Own
                }
            val answer = pin.pinMonotonic(clock.now().value)
            pin.unpin()
            return when (answer) {
                is PinAnswer.Pinned -> Pinned(pin)
                is PinAnswer.Unsupported -> Own
            }
        }
    }
}
