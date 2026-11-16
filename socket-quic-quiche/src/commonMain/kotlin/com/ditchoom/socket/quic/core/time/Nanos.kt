package com.ditchoom.socket.quic.core.time

import kotlin.jvm.JvmInline

/** A reading of a [LoopClock]: always a real time, never a stand-in for "none". */
@JvmInline
internal value class Nanos(
    val value: Long,
) {
    operator fun plus(delta: Long): Nanos = Nanos(value + delta)

    /** Nanoseconds from [earlier] to this time. */
    operator fun minus(earlier: Nanos): Long = value - earlier.value

    operator fun compareTo(other: Nanos): Int = value.compareTo(other.value)
}
