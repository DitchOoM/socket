package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * Allocates a native `sockaddr_in` or `sockaddr_in6` struct via [BufferFactory]
 * and returns both the buffer (for lifecycle management) and the native address + length.
 *
 * Zero-copy: the struct is written directly into a deterministic buffer's native memory.
 * The caller must free the buffer when done.
 *
 * Layout (sockaddr_in, 16 bytes):
 *   [0..1]  sin_family  = AF_INET (2)
 *   [2..3]  sin_port    = port in network byte order (big endian)
 *   [4..7]  sin_addr    = 4-byte IPv4 address
 *   [8..15] sin_zero    = padding
 *
 * Layout (sockaddr_in6, 28 bytes):
 *   [0..1]   sin6_family   = AF_INET6 (10)
 *   [2..3]   sin6_port     = port in network byte order
 *   [4..7]   sin6_flowinfo = 0
 *   [8..23]  sin6_addr     = 16-byte IPv6 address
 *   [24..27] sin6_scope_id = 0
 */
internal data class NativeSockAddr(
    val buffer: PlatformBuffer,
    val address: Long,
    val length: Int,
) {
    fun free() = buffer.freeNativeMemory()
}

internal fun InetSocketAddress.toNativeSockAddr(bufferFactory: BufferFactory): NativeSockAddr {
    val inetAddr = address
    return when (inetAddr) {
        is Inet4Address -> {
            val buf = bufferFactory.allocate(SOCKADDR_IN_SIZE)
            val addrBytes = inetAddr.address // 4 bytes

            // sin_family = AF_INET (2) — little-endian on x86
            buf.writeByte(AF_INET.toByte())
            buf.writeByte(0)
            // sin_port — network byte order (big endian)
            buf.writeByte((port shr 8).toByte())
            buf.writeByte((port and 0xFF).toByte())
            // sin_addr — 4 bytes
            buf.writeBytes(addrBytes)
            // sin_zero — 8 bytes padding
            repeat(8) { buf.writeByte(0) }

            buf.resetForRead()
            val nativeAddr = buf.nativeMemoryAccess!!.nativeAddress.toLong()
            NativeSockAddr(buf, nativeAddr, SOCKADDR_IN_SIZE)
        }
        is Inet6Address -> {
            val buf = bufferFactory.allocate(SOCKADDR_IN6_SIZE)
            val addrBytes = inetAddr.address // 16 bytes

            // sin6_family = AF_INET6 (10)
            buf.writeByte(AF_INET6.toByte())
            buf.writeByte(0)
            // sin6_port — network byte order
            buf.writeByte((port shr 8).toByte())
            buf.writeByte((port and 0xFF).toByte())
            // sin6_flowinfo = 0
            repeat(4) { buf.writeByte(0) }
            // sin6_addr — 16 bytes
            buf.writeBytes(addrBytes)
            // sin6_scope_id = 0
            repeat(4) { buf.writeByte(0) }

            buf.resetForRead()
            val nativeAddr = buf.nativeMemoryAccess!!.nativeAddress.toLong()
            NativeSockAddr(buf, nativeAddr, SOCKADDR_IN6_SIZE)
        }
        else -> throw IllegalArgumentException("Unsupported address type: ${inetAddr::class}")
    }
}

// AF_INET = 2 on Linux/macOS
private const val AF_INET = 2

// AF_INET6 = 10 on Linux, 30 on macOS
private const val AF_INET6 = 10

private const val SOCKADDR_IN_SIZE = 16
private const val SOCKADDR_IN6_SIZE = 28
