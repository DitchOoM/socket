package com.ditchoom.socket.quic.core.time

import kotlin.jvm.JvmInline

/** A reading of a [LoopClock]: always a real time, never a stand-in for "none". */
@JvmInline
internal value class Nanos(
    val value: Long,
) {
    /** [delta] later, held at the latest or earliest time instead of wrapping: an unbounded wait never falls due. */
    operator fun plus(delta: Long): Nanos {
        val sum = value + delta
        // The sum overflowed when both operands have a sign it lacks.
        if ((value xor sum) and (delta xor sum) >= 0) return Nanos(sum)
        return Nanos(if (delta > 0) LATEST else EARLIEST)
    }

    private companion object {
        /** The latest and earliest times a reading can hold. */
        const val LATEST = 0x7FFF_FFFF_FFFF_FFFFL
        const val EARLIEST = -LATEST - 1
    }

    /** Nanoseconds from [earlier] to this time. */
    operator fun minus(earlier: Nanos): Long = value - earlier.value

    operator fun compareTo(other: Nanos): Int = value.compareTo(other.value)
}
