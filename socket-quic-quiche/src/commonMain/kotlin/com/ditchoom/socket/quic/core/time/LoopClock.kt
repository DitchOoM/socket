package com.ditchoom.socket.quic.core.time

/** Whose clock a [LoopClock] reads: what a time libquiche stamps can be compared with. */
internal sealed interface ClockBase {
    /** The platform's monotonic clock as libquiche reads it: its send times are comparable with this clock's readings. */
    data object Libquiche : ClockBase

    /** A clock of its own, such as a test's virtual time: libquiche's send times are not comparable, so nothing is paced by them. */
    data object Own : ClockBase
}

/** The time, as an input, so a pass is a function of what it is given and tests drive time by hand. */
internal fun interface LoopClock {
    fun now(): Nanos

    /** The base this clock counts on; a clock of its own unless it says it reads libquiche's. */
    fun base(): ClockBase = ClockBase.Own
}
