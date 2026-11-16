package com.ditchoom.socket.quic.core.time

/** The time, as an input, so a pass is a function of what it is given and tests drive time by hand. */
internal fun interface LoopClock {
    fun now(): Nanos
}
