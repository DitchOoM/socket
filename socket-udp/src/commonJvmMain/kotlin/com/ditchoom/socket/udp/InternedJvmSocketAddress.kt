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
) : SocketAddress,
    PackedSocketAddress {
    override val host: String get() = inet.address?.hostAddress ?: inet.hostString
    override val port: Int get() = inet.port
    override val family: AddressFamily
        get() = if (inet.address is Inet6Address) AddressFamily.IPv6 else AddressFamily.IPv4

    // Packed address (big-endian; IPv4 in low 32 of packedLo) for SocketAddressCodec, computed once
    // from the interned InetAddress bytes. Only read at the FFI wall (connection setup / per new
    // source), never per packet.
    private val packed: LongArray by lazy {
        val addr = inet.address ?: error("InternedJvmSocketAddress requires a resolved address for sockaddr encoding")

        @Suppress("NoByteArrayInProd") // java.net.InetAddress.getAddress() boundary
        val b = addr.address
        if (b.size == 4) longArrayOf(0L, bigEndian(b, 0, 4)) else longArrayOf(bigEndian(b, 0, 8), bigEndian(b, 8, 8))
    }
    override val packedHi: Long get() = packed[0]
    override val packedLo: Long get() = packed[1]

    // Value semantics: a demux routing table keys by peer. InetSocketAddress compares by resolved
    // address + port, so two InternedJvmSocketAddress over equal endpoints are equal.
    override fun equals(other: Any?): Boolean = other is InternedJvmSocketAddress && inet == other.inet

    override fun hashCode(): Int = inet.hashCode()

    override fun toString(): String = inet.toString()
}

/** Pack [n] big-endian bytes of [b] starting at [offset] into a long. */
private fun bigEndian(
    b: ByteArray,
    offset: Int,
    n: Int,
): Long {
    var v = 0L
    for (i in 0 until n) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
    return v
}

/**
 * The running host-OS C `sockaddr` layout for [SocketAddressCodec] on JVM/Android. Unlike the K/N
 * targets (which know their OS at compile time), a JVM jar runs on Linux, macOS, or Windows, so the
 * `AF_INET6` value and the BSD length-byte convention are detected at runtime from `os.name`. This
 * mirrors the exact detection the quiche `SockAddrUtil` used — a wrong `AF_INET6` (Linux 10 / BSD 30 /
 * Windows 23) makes quiche reject the sockaddr with a hard panic.
 */
@ExperimentalDatagramApi
fun hostOsSockAddrLayout(): SockAddrLayout {
    val osName = System.getProperty("os.name").lowercase()
    val isBsd = osName.contains("mac") || osName.contains("darwin") || osName.contains("bsd")
    val isWindows = osName.contains("win")
    val afInet6 =
        when {
            isBsd -> 30
            isWindows -> 23
            else -> 10
        }
    return SockAddrLayout(hasLenByte = isBsd, afInet = 2, afInet6 = afInet6)
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
