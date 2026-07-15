@file:OptIn(ExperimentalForeignApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.posix.sockaddr_storage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The safety net for the [SocketAddressCodec] byte layout: prove it is **byte-for-byte identical** to
 * the proven [writeSockaddr] (the quiche-lifted native sockaddr writer) for both address families.
 * Because the QUIC cutover feeds the codec's output straight to quiche's `recv_info`/`send_info` FFI,
 * any drift from the layout quiche expects is a hard panic — this test locks the two in step.
 */
class SocketAddressCodecDifferentialTests {
    private val codec = SocketAddressCodec(linuxSockAddrLayout)

    private fun codecBytes(addr: LinuxSocketAddress): List<Byte> {
        val size = if (addr.family == AddressFamily.IPv6) SOCKADDR_IN6_SIZE else SOCKADDR_IN_SIZE
        val buf = PlatformBuffer.allocateNative(size)
        codec.encode(buf, addr, EncodeContext.Empty)
        buf.resetForRead()
        return (0 until size).map { buf.readByte() }
    }

    private fun writeSockaddrBytes(addr: LinuxSocketAddress): List<Byte> =
        memScoped {
            val storage = alloc<sockaddr_storage>()
            val len = addr.writeSockaddr(storage).toInt()
            val bytes = storage.ptr.reinterpret<ByteVar>()
            (0 until len).map { bytes[it] }
        }

    @Test
    fun ipv4_codec_matches_writeSockaddr_byteForByte() {
        val addr = LinuxSocketAddress("127.0.0.1", 8080, AddressFamily.IPv4, hi = 0L, lo = 0x7F000001L)
        assertEquals(SOCKADDR_IN_SIZE, codecBytes(addr).size)
        assertEquals(writeSockaddrBytes(addr), codecBytes(addr))
    }

    @Test
    fun ipv6_codec_matches_writeSockaddr_byteForByte() {
        // 2001:db8::1 → hi = 0x20010db800000000, lo = 0x1
        val addr = LinuxSocketAddress("2001:db8::1", 443, AddressFamily.IPv6, hi = 0x20010db800000000uL.toLong(), lo = 1L)
        assertEquals(SOCKADDR_IN6_SIZE, codecBytes(addr).size)
        assertEquals(writeSockaddrBytes(addr), codecBytes(addr))
    }

    @Test
    fun ipv6_loopback_codec_matches_writeSockaddr() {
        val addr = LinuxSocketAddress("::1", 9000, AddressFamily.IPv6, hi = 0L, lo = 1L)
        assertEquals(writeSockaddrBytes(addr), codecBytes(addr))
    }
}
