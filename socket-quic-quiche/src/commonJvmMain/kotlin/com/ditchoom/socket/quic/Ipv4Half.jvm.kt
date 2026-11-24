package com.ditchoom.socket.quic

import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel

/** An `AF_INET` probe on every JVM: Darwin's dual-stack bind does not own the IPv4 half, and the probe costs a bind. */
internal actual fun ipv4HalfOf(port: Int): Ipv4Half =
    try {
        DatagramChannel.open(StandardProtocolFamily.INET).use { probe ->
            probe.bind(InetSocketAddress(IPV4_WILDCARD, port))
            when (val bound = probe.localAddress) {
                is InetSocketAddress -> Ipv4Half.Free(bound.port)
                else -> Ipv4Half.NotProbed(port)
            }
        }
    } catch (held: BindException) {
        Ipv4Half.Held
    } catch (unprobed: IOException) {
        // No IPv4 on this host, say: the dual-stack bind decides alone.
        Ipv4Half.NotProbed(port)
    }

private const val IPV4_WILDCARD = "0.0.0.0"
