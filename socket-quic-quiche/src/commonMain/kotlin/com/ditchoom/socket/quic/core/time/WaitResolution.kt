package com.ditchoom.socket.quic.core.time

import kotlin.jvm.JvmInline

/** The finest step a loop's timed wait wakes on, on its I/O stack. */
@JvmInline
internal value class WaitResolution(
    val nanos: Long,
)
