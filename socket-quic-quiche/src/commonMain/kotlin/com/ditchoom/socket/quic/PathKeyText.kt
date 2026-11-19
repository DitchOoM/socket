@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.udp.SockAddrLayout
import com.ditchoom.socket.udp.SocketAddressCodec

/** The `sockaddr` layout of the operating system this process runs on. */
internal expect val hostSockAddrLayout: SockAddrLayout

/** Whether this key names no address: the wildcard a socket binds to listen on every interface. */
internal fun PathKey.isWildcard(): Boolean = hi == 0L && lo == 0L

/**
 * The address as text: dotted for IPv4, eight hexadecimal groups without leading zeros for IPv6, which is how
 * `java.net.Inet6Address` writes it, so a host compared as text reads the same on every platform.
 */
internal fun PathKey.hostLiteral(): String =
    when (family) {
        6 -> {
            val out = StringBuilder(IPV6_TEXT)
            for (group in 0 until IPV6_GROUPS) {
                val half = if (group < GROUPS_PER_HALF) hi else lo
                val shift = (GROUPS_PER_HALF - 1 - group % GROUPS_PER_HALF) * BITS_PER_GROUP
                if (group > 0) out.append(':')
                out.append(((half ushr shift) and GROUP_MASK).toString(HEX))
            }
            out.toString()
        }
        else ->
            "${(lo ushr 24) and BYTE_MASK}.${(lo ushr 16) and BYTE_MASK}.${(lo ushr 8) and BYTE_MASK}.${lo and BYTE_MASK}"
    }

internal fun PathKey.toSocketAddress(): SocketAddress = SocketAddress.ofLiteral(hostLiteral(), port)

/**
 * This address as a C `sockaddr` of [hostSockAddrLayout] in native memory from [factory], for quiche: what a
 * connection's peer and local addresses are handed as. A key of no family is written as IPv4.
 */
internal fun PathKey.encodeToNative(factory: BufferFactory): EncodedSockAddr {
    val v6 = family == 6
    val size = if (v6) SOCKADDR_IN6 else SOCKADDR_IN
    val buffer = factory.allocate(size)
    val layout = hostSockAddrLayout
    val af = if (v6) layout.afInet6 else layout.afInet
    if (layout.hasLenByte) {
        buffer.writeByte(size.toByte())
        buffer.writeByte(af.toByte())
    } else {
        buffer.writeByte((af and 0xFF).toByte())
        buffer.writeByte(((af shr 8) and 0xFF).toByte())
    }
    buffer.writeByte(((port shr 8) and 0xFF).toByte())
    buffer.writeByte((port and 0xFF).toByte())
    if (v6) {
        repeat(4) { buffer.writeByte(0) }
        for (i in 0 until LONG_BYTES) buffer.writeByte(((hi ushr (56 - 8 * i)) and BYTE_MASK).toByte())
        for (i in 0 until LONG_BYTES) buffer.writeByte(((lo ushr (56 - 8 * i)) and BYTE_MASK).toByte())
        repeat(4) { buffer.writeByte(0) }
    } else {
        for (i in 0 until V4_BYTES) buffer.writeByte(((lo ushr (24 - 8 * i)) and BYTE_MASK).toByte())
        repeat(8) { buffer.writeByte(0) }
    }
    buffer.resetForRead()
    val address = buffer.nativeMemoryAccess!!.nativeAddress.toLong()
    return EncodedSockAddr(buffer, address, size)
}

/**
 * The key of this resolved address (from `UdpSocket.resolve`), read from its `sockaddr` encoding: the port at bytes 2–3
 * and the address at 4 (IPv4) or 8 (IPv6), big-endian, in every layout. Built where a connection or a server starts,
 * never per datagram.
 */
internal fun SocketAddress.toPathKey(): PathKey {
    val codec = SocketAddressCodec(hostSockAddrLayout)
    val buffer = BufferFactory.Default.allocate(SOCKADDR_STORAGE)
    codec.encode(buffer, this, EncodeContext.Empty)
    val port = ((buffer.get(PORT_AT).toInt() and 0xFF) shl 8) or (buffer.get(PORT_AT + 1).toInt() and 0xFF)
    return when (family) {
        AddressFamily.IPv6 -> PathKey(6, port, bigEndian(buffer, V6_AT), bigEndian(buffer, V6_AT + LONG_BYTES))
        AddressFamily.IPv4 -> {
            var v4 = 0L
            for (i in V4_AT until V4_AT + V4_BYTES) v4 = (v4 shl 8) or (buffer.get(i).toLong() and BYTE_MASK)
            PathKey(4, port, 0L, v4)
        }
    }
}

private fun bigEndian(
    buffer: com.ditchoom.buffer.PlatformBuffer,
    from: Int,
): Long {
    var value = 0L
    for (i in from until from + LONG_BYTES) value = (value shl 8) or (buffer.get(i).toLong() and BYTE_MASK)
    return value
}

private const val SOCKADDR_STORAGE = 128
private const val SOCKADDR_IN = 16
private const val SOCKADDR_IN6 = 28
private const val PORT_AT = 2
private const val V4_AT = 4
private const val V4_BYTES = 4
private const val V6_AT = 8
private const val LONG_BYTES = 8
private const val BYTE_MASK = 0xFFL
private const val IPV6_TEXT = 39
private const val IPV6_GROUPS = 8
private const val GROUPS_PER_HALF = 4
private const val BITS_PER_GROUP = 16
private const val GROUP_MASK = 0xFFFFL
private const val HEX = 16
