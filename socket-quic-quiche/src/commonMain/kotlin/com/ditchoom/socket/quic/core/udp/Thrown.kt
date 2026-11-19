package com.ditchoom.socket.quic.core.udp

/** A platform exception the core has no name for, held where a failure needs it: required, never absent. */
internal class Thrown(
    val throwable: Throwable,
)
