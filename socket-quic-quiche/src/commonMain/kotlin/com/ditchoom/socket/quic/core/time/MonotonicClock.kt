package com.ditchoom.socket.quic.core.time

/**
 * The platform's monotonic clock as the process reads it, not counted from an origin of its own: libquiche stamps each
 * datagram's send time on that clock (`CLOCK_MONOTONIC` on Linux and Android, `CLOCK_UPTIME_RAW` on Apple), and the loop
 * compares the stamps with this one.
 */
internal object MonotonicClock : LoopClock {
    override fun now(): Nanos = Nanos(monotonicNanos())
}

/** One reading of the clock [MonotonicClock] reports, in nanoseconds. */
internal expect fun monotonicNanos(): Long
