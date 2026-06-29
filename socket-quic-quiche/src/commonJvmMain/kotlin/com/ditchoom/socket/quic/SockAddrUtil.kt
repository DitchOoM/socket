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
 * Layout differs between BSD (macOS) and Linux/Windows:
 *
 * Linux/Windows sockaddr_in (16 bytes):
 *   [0..1]  sin_family  = AF_INET (2), little-endian
 *   [2..3]  sin_port, [4..7] sin_addr, [8..15] sin_zero
 *
 * BSD/macOS sockaddr_in (16 bytes):
 *   [0]     sin_len     = 16
 *   [1]     sin_family  = AF_INET (2)
 *   [2..3]  sin_port, [4..7] sin_addr, [8..15] sin_zero
 *
 * Windows winsock has no sin_len (same as Linux), so it uses the non-BSD
 * branch. sin6_family / AF_INET6 differs three ways: 10 on Linux, 30 on BSD,
 * 23 on Windows — see [AF_INET6].
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

/**
 * Reconstruct an [InetSocketAddress] from a [PathKey] decoded via
 * [QuicheApi.decodePathKey]. Inverse of [toNativeSockAddr] + decode: the key's
 * `lo`/`hi` hold the address bytes in canonical network order interpreted
 * big-endian (see the JNI/FFM/cinterop decode), so the high byte is emitted
 * first. Used by the server egress path to turn `sendInfo.to` into a NIO send
 * destination. Returns null for the empty/unknown family (PathKey family 0).
 */
internal fun PathKey.toInetSocketAddress(): InetSocketAddress? {
    val bytes =
        when (family) {
            // java.net.InetAddress requires a ByteArray at this boundary; the 4/16-byte
            // address is tiny and built once per distinct destination (cached upstream).
            4 ->
                @Suppress("NoByteArrayInProd") // java.net.InetAddress.getByAddress boundary
                byteArrayOf(
                    (lo ushr 24).toByte(),
                    (lo ushr 16).toByte(),
                    (lo ushr 8).toByte(),
                    lo.toByte(),
                )
            6 ->
                @Suppress("NoByteArrayInProd") // java.net.InetAddress.getByAddress boundary
                ByteArray(16).also { b ->
                    for (i in 0 until 8) {
                        b[i] = (hi ushr (8 * (7 - i))).toByte()
                        b[i + 8] = (lo ushr (8 * (7 - i))).toByte()
                    }
                }
            else -> return null
        }
    return InetSocketAddress(java.net.InetAddress.getByAddress(bytes), port)
}

private val IS_BSD: Boolean by lazy {
    System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") || it.contains("bsd") }
}

private val IS_WINDOWS: Boolean by lazy {
    System.getProperty("os.name").lowercase().contains("win")
}

// AF_INET = 2 on every POSIX and on Windows winsock.
private const val AF_INET = 2

// AF_INET6 is platform-specific: Linux 10, BSD/Darwin 30, Windows (winsock) 23.
// quiche compares sa_family against the value baked in for the target it was
// compiled for, so a mismatch makes it reject the sockaddr with "unsupported
// address type" (ffi.rs:2059) — a hard panic that crashes the JVM. This is not
// theoretical on Windows: the JVM opens dual-stack IPv6 DatagramChannels by
// default there, so channel.localAddress is routinely an Inet6Address even for
// an IPv4 peer, forcing the IPv6 branch. Getting 23 right is mandatory.
private val AF_INET6: Int =
    when {
        IS_BSD -> 30
        IS_WINDOWS -> 23
        else -> 10
    }

private const val SOCKADDR_IN_SIZE = 16
private const val SOCKADDR_IN6_SIZE = 28
