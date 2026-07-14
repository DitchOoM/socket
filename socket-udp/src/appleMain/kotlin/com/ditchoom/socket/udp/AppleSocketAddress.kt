@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.SocketAddressResolver
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.AI_NUMERICHOST
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socklen_t

/**
 * The Apple/K-N [SocketAddress] actual (RFC §4) — the Darwin twin of [com.ditchoom.socket.udp] Linux's
 * `LinuxSocketAddress`. Stores the resolved endpoint as **primitives** (`family`, `port`, address
 * packed into `hi`/`lo`), so there is no pinned native `sockaddr` to free (GC-safe, no `Cleaner`) and
 * reuse as a send target is a zero-alloc materialization into a channel-reused scratch via
 * [writeSockaddr].
 *
 * BSD/Darwin sockaddr layout differs from Linux only in the first two bytes: `sa_len` (byte 0) +
 * single-byte `sa_family` (byte 1, `AF_INET`=2 / `AF_INET6`=30) vs Linux's uint16 `sa_family` at
 * offset 0. Port and address bytes are identical (network order at offsets 2 / 4 / 8).
 */
@ExperimentalDatagramApi
internal class AppleSocketAddress(
    override val host: String,
    override val port: Int,
    override val family: AddressFamily,
    val hi: Long,
    val lo: Long,
) : SocketAddress, PackedSocketAddress {
    override val packedHi: Long get() = hi
    override val packedLo: Long get() = lo

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppleSocketAddress) return false
        return port == other.port && family == other.family && hi == other.hi && lo == other.lo
    }

    override fun hashCode(): Int {
        var h = (hi xor lo).hashCode()
        h = 31 * h + port
        h = 31 * h + family.ordinal
        return h
    }

    override fun toString(): String = if (family == AddressFamily.IPv6) "[$host]:$port" else "$host:$port"
}

/**
 * The BSD/Darwin C `sockaddr` layout for [SocketAddressCodec]: a length byte, a single-byte
 * `sa_family`, `AF_INET6` = 30.
 */
@ExperimentalDatagramApi
val appleSockAddrLayout: SockAddrLayout = SockAddrLayout(hasLenByte = true, afInet = AF_INET, afInet6 = AF_INET6)

/**
 * Materialize [this] address into [storage] as BSD/Darwin network-order `sockaddr` bytes; return its
 * length — the send-target fast path (zero-alloc; [storage] is a channel-reused scratch). A foreign
 * [SocketAddress] (a buffer-flow literal) is re-parsed from its numeric [SocketAddress.host].
 */
@ExperimentalDatagramApi
internal fun SocketAddress.writeSockaddr(storage: sockaddr_storage): socklen_t {
    val apple = this as? AppleSocketAddress ?: parseNumericHost(host, port)
    val bytes = storage.ptr.reinterpret<ByteVar>()
    for (i in 0 until sizeOf<sockaddr_storage>().toInt()) bytes[i] = 0
    return when (apple.family) {
        AddressFamily.IPv4 -> {
            val len = sizeOf<sockaddr_in>().toInt()
            bytes[0] = (len and 0xFF).toByte() // sin_len
            bytes[1] = (AF_INET and 0xFF).toByte() // sin_family (single byte on BSD)
            bytes[2] = ((apple.port shr 8) and 0xFF).toByte()
            bytes[3] = (apple.port and 0xFF).toByte()
            for (i in 0 until 4) bytes[4 + i] = ((apple.lo shr (24 - 8 * i)) and 0xFF).toByte()
            len.convert()
        }
        AddressFamily.IPv6 -> {
            val len = sizeOf<sockaddr_in6>().toInt()
            bytes[0] = (len and 0xFF).toByte() // sin6_len
            bytes[1] = (AF_INET6 and 0xFF).toByte() // sin6_family
            bytes[2] = ((apple.port shr 8) and 0xFF).toByte()
            bytes[3] = (apple.port and 0xFF).toByte()
            // offset 4..7 sin6_flowinfo = 0 (already zeroed)
            for (i in 0 until 8) bytes[8 + i] = ((apple.hi shr (56 - 8 * i)) and 0xFF).toByte()
            for (i in 0 until 8) bytes[16 + i] = ((apple.lo shr (56 - 8 * i)) and 0xFF).toByte()
            len.convert()
        }
    }
}

/**
 * Decode a kernel/NW-filled BSD `sockaddr` (from `recvfrom`/`getsockname`/`nw_udp_copy_*_sockaddr`) into
 * an [AppleSocketAddress], extracting the endpoint into primitives immediately so the source storage may
 * be reused. Returns `null` for a family other than IPv4/IPv6.
 */
@ExperimentalDatagramApi
internal fun sockaddrToAppleSocketAddress(addr: CPointer<sockaddr>): AppleSocketAddress? {
    val bytes = addr.reinterpret<ByteVar>()
    val family = bytes[1].toInt() and 0xFF // sa_family is the second byte on BSD
    val port = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
    return when (family) {
        AF_INET -> {
            var lo = 0L
            for (i in 0 until 4) lo = (lo shl 8) or (bytes[4 + i].toLong() and 0xFF)
            AppleSocketAddress(formatIpv4(lo), port, AddressFamily.IPv4, 0L, lo)
        }
        AF_INET6 -> {
            var hi = 0L
            var lo = 0L
            for (i in 0 until 8) hi = (hi shl 8) or (bytes[8 + i].toLong() and 0xFF)
            for (i in 0 until 8) lo = (lo shl 8) or (bytes[16 + i].toLong() and 0xFF)
            AppleSocketAddress(formatIpv6(hi, lo), port, AddressFamily.IPv6, hi, lo)
        }
        else -> null
    }
}

/** Dotted-quad from the low 32 bits of [lo]. */
private fun formatIpv4(lo: Long): String {
    val b0 = (lo shr 24) and 0xFF
    val b1 = (lo shr 16) and 0xFF
    val b2 = (lo shr 8) and 0xFF
    val b3 = lo and 0xFF
    return "$b0.$b1.$b2.$b3"
}

/** Full (uncompressed) 8-group hex form of the 16 address bytes in [hi]+[lo] — inspectable, not equality-relevant. */
private fun formatIpv6(
    hi: Long,
    lo: Long,
): String {
    val groups = IntArray(8)
    for (i in 0 until 4) groups[i] = ((hi shr (48 - 16 * i)) and 0xFFFF).toInt()
    for (i in 0 until 4) groups[4 + i] = ((lo shr (48 - 16 * i)) and 0xFFFF).toInt()
    return groups.joinToString(":") { it.toString(16) }
}

@ExperimentalDatagramApi
private fun parseNumericHost(
    host: String,
    port: Int,
): AppleSocketAddress =
    resolveViaGetaddrinfo(host, port, numericOnly = true)
        ?: throw IllegalArgumentException("Not a valid numeric IP: '$host'")

/**
 * Resolve [host]:[port] through `getaddrinfo` into an [AppleSocketAddress]. [numericOnly] adds
 * `AI_NUMERICHOST` to skip DNS (the literal fast path). Prefers the first IPv4 result for loopback
 * determinism. Returns `null` on failure.
 */
@ExperimentalDatagramApi
internal fun resolveViaGetaddrinfo(
    host: String,
    port: Int,
    numericOnly: Boolean,
): AppleSocketAddress? =
    memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_DGRAM
        if (numericOnly) hints.ai_flags = AI_NUMERICHOST
        val resultPtr = allocPointerTo<addrinfo>()
        if (getaddrinfo(host, port.toString(), hints.ptr, resultPtr.ptr) != 0) return@memScoped null
        try {
            var chosen: CPointer<sockaddr>? = null
            var node: CPointer<addrinfo>? = resultPtr.value
            while (node != null) {
                val ai = node.pointed
                val sa = ai.ai_addr
                if (sa != null) {
                    if (chosen == null) chosen = sa
                    if (ai.ai_family == AF_INET) {
                        chosen = sa
                        break
                    }
                }
                node = ai.ai_next
            }
            chosen?.let { sockaddrToAppleSocketAddress(it) }
        } finally {
            freeaddrinfo(resultPtr.value)
        }
    }

/**
 * The Apple hostname resolver installed into buffer-flow (RFC §10.1). Numeric literals resolve with no
 * lookup; hostnames perform real DNS via `getaddrinfo` off the caller's dispatcher.
 */
@ExperimentalDatagramApi
internal object AppleSocketAddressResolver : SocketAddressResolver {
    override suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress {
        require(port in 0..65535) { "port out of range: $port" }
        return withContext(Dispatchers.Default) {
            resolveViaGetaddrinfo(host, port, numericOnly = false)
                ?: throw IllegalArgumentException("Could not resolve host: '$host'")
        }
    }
}
