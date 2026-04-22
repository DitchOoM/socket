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
 * Layout differs between BSD (macOS) and Linux:
 *
 * Linux sockaddr_in (16 bytes):
 *   [0..1]  sin_family  = AF_INET (2), little-endian
 *   [2..3]  sin_port, [4..7] sin_addr, [8..15] sin_zero
 *
 * BSD/macOS sockaddr_in (16 bytes):
 *   [0]     sin_len     = 16
 *   [1]     sin_family  = AF_INET (2)
 *   [2..3]  sin_port, [4..7] sin_addr, [8..15] sin_zero
 *
 * sin6_family / AF_INET6 also differ: 10 on Linux, 30 on BSD.
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

            if (IS_BSD) {
                buf.writeByte(SOCKADDR_IN_SIZE.toByte()) // sin_len
                buf.writeByte(AF_INET.toByte()) // sin_family
            } else {
                // Linux: sin_family as uint16 little-endian
                buf.writeByte(AF_INET.toByte())
                buf.writeByte(0)
            }
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

            if (IS_BSD) {
                buf.writeByte(SOCKADDR_IN6_SIZE.toByte()) // sin6_len
                buf.writeByte(AF_INET6.toByte()) // sin6_family
            } else {
                buf.writeByte(AF_INET6.toByte())
                buf.writeByte(0)
            }
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

private val IS_BSD: Boolean by lazy {
    System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") || it.contains("bsd") }
}

// AF_INET = 2 on every POSIX
private const val AF_INET = 2

// AF_INET6 is platform-specific
private val AF_INET6: Int = if (System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") || it.contains("bsd") }) 30 else 10

private const val SOCKADDR_IN_SIZE = 16
private const val SOCKADDR_IN6_SIZE = 28
