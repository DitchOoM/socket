package com.ditchoom.socket.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNative
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SocketAddressCodec] on JVM/Android: byte-layout invariants against the host-OS layout, encode/decode
 * roundtrip, the packed-address derivation from an interned `InetAddress`, and the guard that only
 * `:socket-udp` resolved addresses are encodable.
 */
@OptIn(ExperimentalDatagramApi::class)
class SocketAddressCodecTests {
    private val layout = hostOsSockAddrLayout()
    private val codec = SocketAddressCodec(layout)

    private fun jvmAddr(
        ip: String,
        port: Int,
    ) = InternedJvmSocketAddress(InetSocketAddress(InetAddress.getByName(ip), port))

    private fun encodeToBytes(addr: SocketAddress): List<Int> {
        val size = if (addr.family == AddressFamily.IPv6) SOCKADDR_IN6_SIZE else SOCKADDR_IN_SIZE
        val buf = PlatformBuffer.allocateNative(size)
        codec.encode(buf, addr, EncodeContext.Empty)
        buf.resetForRead()
        return (0 until size).map { buf.readByte().toInt() and 0xFF }
    }

    @Test
    fun ipv4_layout_port_and_address_bytes() {
        val bytes = encodeToBytes(jvmAddr("127.0.0.1", 8080))
        assertEquals(SOCKADDR_IN_SIZE, bytes.size)
        // family field
        if (layout.hasLenByte) {
            assertEquals(SOCKADDR_IN_SIZE, bytes[0]) // sin_len
            assertEquals(layout.afInet, bytes[1]) // single-byte family
        } else {
            assertEquals(layout.afInet, bytes[0]) // uint16 family, host order (LE)
            assertEquals(0, bytes[1])
        }
        // port 8080 = 0x1F90, network byte order
        assertEquals(0x1F, bytes[2])
        assertEquals(0x90, bytes[3])
        // address 127.0.0.1
        assertEquals(listOf(127, 0, 0, 1), bytes.subList(4, 8))
        // sin_zero
        assertEquals(List(8) { 0 }, bytes.subList(8, 16))
    }

    @Test
    fun ipv6_layout_family_and_address_bytes() {
        val bytes = encodeToBytes(jvmAddr("2001:db8::1", 443))
        assertEquals(SOCKADDR_IN6_SIZE, bytes.size)
        val af = if (layout.hasLenByte) bytes[1] else (bytes[0] or (bytes[1] shl 8))
        assertEquals(layout.afInet6, af)
        // port 443 = 0x01BB
        assertEquals(0x01, bytes[2])
        assertEquals(0xBB, bytes[3])
        // flowinfo zero
        assertEquals(List(4) { 0 }, bytes.subList(4, 8))
        // 2001:0db8:0000...:0001
        assertEquals(listOf(0x20, 0x01, 0x0d, 0xb8), bytes.subList(8, 12))
        assertEquals(0x01, bytes[23])
        // scope_id zero
        assertEquals(List(4) { 0 }, bytes.subList(24, 28))
    }

    @Test
    fun packed_address_derivation_from_inet() {
        val v4 = jvmAddr("127.0.0.1", 1)
        assertEquals(0L, v4.packedHi)
        assertEquals(0x7F000001L, v4.packedLo)
        val v6 = jvmAddr("2001:db8::1", 1)
        assertEquals(0x20010db800000000uL.toLong(), v6.packedHi)
        assertEquals(1L, v6.packedLo)
    }

    @Test
    fun roundtrip_ipv4() {
        val buf = PlatformBuffer.allocateNative(SOCKADDR_IN_SIZE)
        codec.encode(buf, jvmAddr("192.168.1.5", 5555), EncodeContext.Empty)
        buf.resetForRead()
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals("192.168.1.5", decoded.host)
        assertEquals(5555, decoded.port)
        assertEquals(AddressFamily.IPv4, decoded.family)
    }

    @Test
    fun roundtrip_ipv6() {
        val buf = PlatformBuffer.allocateNative(SOCKADDR_IN6_SIZE)
        codec.encode(buf, jvmAddr("2001:db8::1", 443), EncodeContext.Empty)
        buf.resetForRead()
        val decoded = codec.decode(buf, DecodeContext.Empty)
        assertEquals(443, decoded.port)
        assertEquals(AddressFamily.IPv6, decoded.family)
        // decoded host re-parses to the same address
        assertEquals(SocketAddress.ofLiteral("2001:db8::1", 443).family, decoded.family)
    }

    @Test
    fun encode_rejects_foreign_socketaddress() {
        val foreign = SocketAddress.ofLiteral("10.0.0.1", 80) // buffer-flow LiteralSocketAddress, not :socket-udp
        assertFailsWith<EncodeException> {
            codec.encode(PlatformBuffer.allocateNative(SOCKADDR_IN_SIZE), foreign, EncodeContext.Empty)
        }
    }

    @Test
    fun ipv6_roundtrip_host_reparses_equal() {
        val original = jvmAddr("2001:db8::1", 443)
        val buf = PlatformBuffer.allocateNative(SOCKADDR_IN6_SIZE)
        codec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = codec.decode(buf, DecodeContext.Empty)
        // re-resolving the decoded numeric host through the JVM yields the same interned endpoint
        val reInterned = jvmAddr(decoded.host, decoded.port)
        assertTrue(original == reInterned)
    }
}
