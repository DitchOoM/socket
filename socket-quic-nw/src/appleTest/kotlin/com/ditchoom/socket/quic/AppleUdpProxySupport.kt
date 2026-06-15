@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.bind
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval

// Shared POSIX-UDP helpers for the Apple QUIC impairment / passive-migration proxies (issue #112).
//
// The Linux siblings use the repo's io_uring primitives; Apple K/N uses plain blocking
// `recvfrom`/`sendto` on a recv timeout so the pump loops stay cancellable. Note Darwin's
// `htonl`/`htons` are macros (not K/N symbols) and all Apple targets are little-endian, so the
// network-byte-order (big-endian) values are built directly here.

// 64 KiB: on loopback Network.framework is not path-MTU-constrained and can emit QUIC/UDP
// datagrams far larger than the ~1350 quiche uses, so a smaller buffer would truncate them in
// recvfrom (silent corruption → the QUIC peer can never make progress). (Issue #112.)
internal const val PROXY_MAX_DATAGRAM = 65535

/** 127.0.0.1 as an `in_addr_t` already in network byte order (bytes 7f 00 00 01 on a little-endian host). */
private const val LOOPBACK_NET_ADDR: UInt = 0x0100007fu

/** Host → network byte order for a 16-bit port (Apple is little-endian, so swap the two bytes). */
internal fun netPort(port: Int): UShort = (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).toUShort()

/** Network → host byte order for a 16-bit port. */
internal fun hostPort(net: UShort): Int {
    val n = net.toInt() and 0xFFFF
    return ((n and 0xFF) shl 8) or ((n ushr 8) and 0xFF)
}

/** A UDP socket bound to an ephemeral loopback port (the proxy's client-facing socket). */
internal fun proxyOpenBoundLoopbackSocket(): Int =
    memScoped {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "proxy client-facing socket() failed" }
        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().convert()
        addr.sin_family = AF_INET.convert()
        addr.sin_addr.s_addr = LOOPBACK_NET_ADDR
        addr.sin_port = netPort(0)
        check(bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "proxy client-facing bind() failed" }
        fd
    }

/** A UDP socket `connect`ed to the loopback server (the proxy's upstream socket). */
internal fun proxyNewUpstream(serverPort: Int): Int =
    memScoped {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "proxy upstream socket() failed" }
        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().convert()
        addr.sin_family = AF_INET.convert()
        addr.sin_addr.s_addr = LOOPBACK_NET_ADDR
        addr.sin_port = netPort(serverPort)
        check(connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "proxy upstream connect() failed" }
        fd
    }

/** The local port a socket is bound to (host byte order). */
internal fun proxyBoundPort(fd: Int): Int =
    memScoped {
        val addr = alloc<sockaddr_in>()
        val len = alloc<socklen_tVar>()
        len.value = sizeOf<sockaddr_in>().convert()
        getsockname(fd, addr.ptr.reinterpret<sockaddr>(), len.ptr)
        hostPort(addr.sin_port)
    }

/** Give [fd] a receive timeout so a blocking `recvfrom` returns periodically — keeps pump loops cancellable. */
internal fun proxySetRecvTimeout(
    fd: Int,
    millis: Int,
) = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (millis / 1000).convert()
    tv.tv_usec = ((millis % 1000) * 1000).convert()
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
}

/** The native address of a deterministic [PlatformBuffer] as a `char *`, for `recvfrom`/`sendto`. */
internal fun PlatformBuffer.nativeBytePtr(): CPointer<ByteVar> = nativeMemoryAccess!!.nativeAddress.toCPointer()!!
