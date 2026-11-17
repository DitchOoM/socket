package com.ditchoom.socket.quic.core.time

import kotlin.jvm.JvmInline

/** The round trip, in nanoseconds, a path is assumed to have before its first sample; [CONFIGURED] keeps quiche's. */
@JvmInline
internal value class RttPrior(
    val nanos: Long,
) {
    companion object {
        val CONFIGURED = RttPrior(0L)
    }
}
