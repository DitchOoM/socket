package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A JVM/Android [SocketAddress] that **owns** an interned [InetSocketAddress] (RFC §4).
 *
 * Constructed once, out of band (via [UdpSocket.resolve] or recovered from a received packet's source),
 * so reuse as a send target is a field read off [inet] — never a resolve-and-pin. That is exactly what
 * makes fan-out to many distinct peers zero-alloc and lets the JVM datagram channel DROP the 1-entry
 * `lastDest` cache the quiche `NioUdpChannel` had to carry. Proven zero-alloc for 1000 distinct
 * destinations by buffer-flow's `SocketAddressAllocationTest`, whose private prototype this materializes.
 */
@ExperimentalDatagramApi
internal class InternedJvmSocketAddress(
    val inet: InetSocketAddress,
) : SocketAddress {
    override val host: String get() = inet.address?.hostAddress ?: inet.hostString
    override val port: Int get() = inet.port
    override val family: AddressFamily
        get() = if (inet.address is Inet6Address) AddressFamily.IPv6 else AddressFamily.IPv4

    // Value semantics: a demux routing table keys by peer. InetSocketAddress compares by resolved
    // address + port, so two InternedJvmSocketAddress over equal endpoints are equal.
    override fun equals(other: Any?): Boolean = other is InternedJvmSocketAddress && inet == other.inet

    override fun hashCode(): Int = inet.hashCode()

    override fun toString(): String = inet.toString()
}

/**
 * The JVM send-target SPI: extract an [InetSocketAddress] from any [SocketAddress].
 *
 * Fast path — an [InternedJvmSocketAddress] yields its owned [InternedJvmSocketAddress.inet] with a
 * field read (the zero-alloc many-dest path). Otherwise (a foreign buffer-flow literal reaching a real
 * socket) derive one from the resolved [SocketAddress.host]:[SocketAddress.port]. A resolved address
 * always carries a numeric host, so [InetAddress.getByName] is a literal parse, not a DNS lookup.
 */
@ExperimentalDatagramApi
internal fun SocketAddress.toInetSocketAddress(): InetSocketAddress =
    when (this) {
        is InternedJvmSocketAddress -> inet
        else -> InetSocketAddress(InetAddress.getByName(host), port)
    }
