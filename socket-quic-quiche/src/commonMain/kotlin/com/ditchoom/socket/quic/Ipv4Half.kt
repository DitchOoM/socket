package com.ditchoom.socket.quic

/**
 * Where the IPv4 half of a dual-stack wildcard port stands, asked of an `AF_INET` probe before the dual-stack bind. On
 * Darwin a `[::]:P` bind succeeds while an `AF_INET` wildcard socket holds `0.0.0.0:P`, which then receives every IPv4
 * datagram for P (#450): so the port is drawn from the IPv4 table first, and a requested port whose IPv4 half is held
 * is refused as in use, as Linux's own bind refuses it.
 */
internal sealed interface Ipv4Half {
    /** The IPv4 half of [port] was free when probed; for a request of port 0, [port] is the one the probe drew. */
    class Free(
        val port: Int,
    ) : Ipv4Half

    /** An IPv4 socket holds the port. */
    data object Held : Ipv4Half

    /** The platform's own dual-stack bind refuses a held IPv4 half, so nothing was probed: bind [port] as asked. */
    class NotProbed(
        val port: Int,
    ) : Ipv4Half
}

/** The IPv4 half of the wildcard [port], probed on the platforms whose dual-stack bind does not own it; blocks briefly. */
internal expect fun ipv4HalfOf(port: Int): Ipv4Half
