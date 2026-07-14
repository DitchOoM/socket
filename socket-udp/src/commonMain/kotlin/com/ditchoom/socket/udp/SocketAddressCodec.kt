/*
 * The type-safe sockaddr SPI for the QUIC cutover (RFC §4). A resolved SocketAddress is passed
 * around as the currency; only at a native-engine FFI wall (quiche's recv_info.from / send_info.to)
 * is it materialized into C-sockaddr bytes, via this buffer-codec Codec. This replaces the RFC's
 * hand-wavy `nativeSockAddr(): Pair<Long, Int>` (allocating, untyped, raw-pointer-as-currency) and
 * supersedes the hand-rolled byte-poking previously duplicated in quiche's SockAddrUtil (JVM) and
 * writeSockaddr (native). One tested codec, differential-checked byte-for-byte against the proven
 * originals. Reusable by ../webrtc (ICE/DTLS also hand sockaddrs to a native engine).
 */
package com.ditchoom.socket.udp

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/** `sizeof(sockaddr_in)` — IPv4 wire length. */
const val SOCKADDR_IN_SIZE: Int = 16

/** `sizeof(sockaddr_in6)` — IPv6 wire length. */
const val SOCKADDR_IN6_SIZE: Int = 28

/**
 * The platform-specific bits of the C `sockaddr` layout, supplied per target so [SocketAddressCodec]
 * itself stays pure-common.
 *
 * @property hasLenByte BSD/Darwin prefix the struct with a 1-byte length then a 1-byte `sa_family`
 *   (`sin_len`/`sin6_len`); Linux/Windows use a 2-byte `sa_family` (host byte order) with no length
 *   byte.
 * @property afInet the OS `AF_INET` constant (2 everywhere).
 * @property afInet6 the OS `AF_INET6` constant — **platform-critical**: Linux 10, BSD/Darwin 30,
 *   Windows 23. quiche compares `sa_family` against the value baked into the target it was compiled
 *   for; a mismatch makes it reject the sockaddr ("unsupported address type", ffi.rs) — a hard panic.
 */
data class SockAddrLayout(
    val hasLenByte: Boolean,
    val afInet: Int,
    val afInet6: Int,
)

/**
 * Internal seam: `:socket-udp`'s [SocketAddress] actuals expose their resolved address as two longs
 * (big-endian; IPv4 in the low 32 bits of [packedLo], [packedHi]=0) so [SocketAddressCodec] can emit
 * the address bytes without re-parsing [SocketAddress.host]. Mirrors the `LiteralSocketAddress`
 * (family, hi, lo) / quiche `PathKey` encoding. Module-internal — not part of the public surface.
 */
internal interface PackedSocketAddress {
    val packedHi: Long
    val packedLo: Long
}

/**
 * Encodes/decodes a [SocketAddress] as native C `sockaddr` bytes for handoff to a native transport
 * engine's FFI (quiche `recv_info`/`send_info`). Construct with the running target's [SockAddrLayout]
 * (from `linuxSockAddrLayout` / `appleSockAddrLayout` / `hostOsSockAddrLayout()`), write into a
 * **native** buffer (`PlatformBuffer.allocateNative`), and hand the engine `nativeAddress` +
 * [wireSize]; quiche's `recv_info` copies the bytes inline, so the buffer need only outlive the call.
 *
 * Byte layout (network order for port/address, matching the proven quiche originals):
 * IPv4 (16 B): family-field(2) · port(2) · addr(4) · zero(8).
 * IPv6 (28 B): family-field(2) · port(2) · flowinfo(4=0) · addr(16) · scope_id(4=0).
 * The family-field is `[len][family]` on BSD, `[family_lo][family_hi]` (host order) elsewhere.
 *
 * [encode] requires a `:socket-udp` resolved [SocketAddress] (an in-repo actual); a foreign
 * buffer-flow literal throws [EncodeException] — resolve it through `UdpSocket` first.
 */
@ExperimentalDatagramApi
class SocketAddressCodec(
    private val layout: SockAddrLayout,
) : Codec<SocketAddress> {
    override fun wireSize(
        value: SocketAddress,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(if (value.family == AddressFamily.IPv6) SOCKADDR_IN6_SIZE else SOCKADDR_IN_SIZE)

    override fun encode(
        buffer: WriteBuffer,
        value: SocketAddress,
        context: EncodeContext,
    ) {
        val packed =
            value as? PackedSocketAddress
                ?: throw EncodeException(
                    fieldPath = "sockaddr",
                    reason =
                        "SocketAddressCodec encodes only :socket-udp resolved addresses; got " +
                            "${value::class.simpleName}. Resolve via UdpSocket.resolve/connect first.",
                )
        val v6 = value.family == AddressFamily.IPv6
        val af = if (v6) layout.afInet6 else layout.afInet
        val size = if (v6) SOCKADDR_IN6_SIZE else SOCKADDR_IN_SIZE
        // family field
        if (layout.hasLenByte) {
            buffer.writeByte(size.toByte()) // sin_len / sin6_len
            buffer.writeByte((af and 0xFF).toByte()) // single-byte sa_family (BSD)
        } else {
            buffer.writeByte((af and 0xFF).toByte()) // sa_family as uint16, host order (LE targets)
            buffer.writeByte(((af shr 8) and 0xFF).toByte())
        }
        // port — network byte order
        buffer.writeByte(((value.port shr 8) and 0xFF).toByte())
        buffer.writeByte((value.port and 0xFF).toByte())
        if (v6) {
            repeat(4) { buffer.writeByte(0) } // sin6_flowinfo = 0
            for (i in 0 until 8) buffer.writeByte(((packed.packedHi shr (56 - 8 * i)) and 0xFF).toByte())
            for (i in 0 until 8) buffer.writeByte(((packed.packedLo shr (56 - 8 * i)) and 0xFF).toByte())
            repeat(4) { buffer.writeByte(0) } // sin6_scope_id = 0
        } else {
            for (i in 0 until 4) buffer.writeByte(((packed.packedLo shr (24 - 8 * i)) and 0xFF).toByte())
            repeat(8) { buffer.writeByte(0) } // sin_zero
        }
    }

    /**
     * Decode C `sockaddr` bytes into a [SocketAddress]. Provided for symmetry, testing, and generic
     * codec use; the QUIC datapath does not use it (received sources arrive already decoded as
     * `Datagram.peer`, and quiche's own `send_info` decode stays internal to the driver). Produces a
     * common literal via [SocketAddress.ofLiteral]; the numeric host is reconstructed from the bytes.
     */
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): SocketAddress {
        val b0 = buffer.readByte().toInt() and 0xFF
        val b1 = buffer.readByte().toInt() and 0xFF
        val af = if (layout.hasLenByte) b1 else (b0 or (b1 shl 8))
        val port = ((buffer.readByte().toInt() and 0xFF) shl 8) or (buffer.readByte().toInt() and 0xFF)
        return if (af == layout.afInet6) {
            repeat(4) { buffer.readByte() } // skip flowinfo
            val words = IntArray(8)
            for (i in 0 until 8) {
                val hiB = buffer.readByte().toInt() and 0xFF
                val loB = buffer.readByte().toInt() and 0xFF
                words[i] = (hiB shl 8) or loB
            }
            val host = words.joinToString(":") { it.toString(16) }
            SocketAddress.ofLiteral(host, port)
        } else {
            val a = buffer.readByte().toInt() and 0xFF
            val b = buffer.readByte().toInt() and 0xFF
            val c = buffer.readByte().toInt() and 0xFF
            val d = buffer.readByte().toInt() and 0xFF
            SocketAddress.ofLiteral("$a.$b.$c.$d", port)
        }
    }
}
