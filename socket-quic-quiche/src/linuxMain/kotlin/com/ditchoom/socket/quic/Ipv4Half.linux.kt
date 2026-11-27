package com.ditchoom.socket.quic

/** Linux's dual-stack bind refuses a port whose IPv4 half is held, and draws an ephemeral port free in both. */
internal actual fun ipv4HalfOf(port: Int): Ipv4Half = Ipv4Half.NotProbed(port)
