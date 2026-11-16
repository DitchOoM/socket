package com.ditchoom.socket.quic.core.udp

/** A socket address in native memory, as quiche reads it. */
internal class SockAddr(
    val address: Long,
    val length: Int,
)
