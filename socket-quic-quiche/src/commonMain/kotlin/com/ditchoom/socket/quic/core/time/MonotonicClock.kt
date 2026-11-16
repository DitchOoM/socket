package com.ditchoom.socket.quic.core.time

import kotlin.time.TimeSource

/** The platform's monotonic clock, counted from the first use of this object. */
internal object MonotonicClock : LoopClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun now(): Nanos = Nanos((TimeSource.Monotonic.markNow() - origin).inWholeNanoseconds)
}
