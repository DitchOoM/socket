@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EADDRINUSE
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.sockaddr_in
import platform.posix.socket

/** An `AF_INET` probe bound to `0.0.0.0:port` and closed: Darwin's dual-stack bind does not own the IPv4 half. */
internal actual fun ipv4HalfOf(port: Int): Ipv4Half {
    val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    if (fd < 0) return Ipv4Half.NotProbed(port)
    try {
        return memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = networkOrder(port).convert()
            addr.sin_addr.s_addr = 0u
            if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                return@memScoped if (errno == EADDRINUSE) Ipv4Half.Held else Ipv4Half.NotProbed(port)
            }
            val len = alloc<UIntVar>()
            len.value = sizeOf<sockaddr_in>().convert()
            if (getsockname(fd, addr.ptr.reinterpret(), len.ptr) != 0) return@memScoped Ipv4Half.NotProbed(port)
            Ipv4Half.Free(networkOrder(addr.sin_port.toInt()))
        }
    } finally {
        close(fd)
    }
}

/** [port]'s 16 bits in the other byte order: network order from host order on a little-endian Apple device, and back. */
private fun networkOrder(port: Int): Int = ((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)
