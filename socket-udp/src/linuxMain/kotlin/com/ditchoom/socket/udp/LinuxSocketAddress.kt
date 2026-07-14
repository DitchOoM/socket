@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.SocketAddressResolver
import com.ditchoom.socket.udp.linux.inet_ntop
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.AI_NUMERICHOST
import platform.posix.INET6_ADDRSTRLEN
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
 * The Linux/K-N [SocketAddress] actual (RFC §4). Stores the resolved endpoint as **primitives** —
 * `family`, `port`, and the address packed into two longs (`hi`/`lo`, big-endian, IPv4 in the low 32
 * bits of [lo]) — mirroring buffer-flow's common `LiteralSocketAddress`. No pinned native `sockaddr`
 * is held, so there is nothing to free (GC-safe, no `Cleaner`): reuse as a send target is a
 * zero-alloc materialization of those primitives into a channel's reused scratch `sockaddr` via
 * [writeSockaddr]. That is what deletes the quiche `PathKey → sockaddr` 1-entry `lastDest` cache.
 */
@ExperimentalDatagramApi
internal class LinuxSocketAddress(
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
        if (other !is LinuxSocketAddress) return false
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
 * The Linux C `sockaddr` layout for [SocketAddressCodec]: no length byte, 2-byte host-order
 * `sa_family`, `AF_INET6` = 10.
 */
@ExperimentalDatagramApi
val linuxSockAddrLayout: SockAddrLayout = SockAddrLayout(hasLenByte = false, afInet = AF_INET, afInet6 = AF_INET6)

/**
 * Materialize [this] address into [storage] as network-order `sockaddr` bytes and return its length —
 * the send-target fast path (zero-alloc; [storage] is a channel-reused scratch buffer). Byte layout
 * matches the proven quiche `PathKey.toSockAddrBuffer` encoding: family in host order (Linux is LE),
 * port and address in network order. A foreign [SocketAddress] (a buffer-flow literal reaching a real
 * socket) is re-parsed from its numeric [SocketAddress.host].
 */
@ExperimentalDatagramApi
internal fun SocketAddress.writeSockaddr(storage: sockaddr_storage): socklen_t {
    val linux = this as? LinuxSocketAddress ?: parseNumericHost(host, port)
    val bytes = storage.ptr.reinterpret<ByteVar>()
    // zero the whole storage so unwritten trailing fields (flowinfo, scope_id) are 0
    for (i in 0 until sizeOf<sockaddr_storage>().toInt()) bytes[i] = 0
    return when (linux.family) {
        AddressFamily.IPv4 -> {
            bytes[0] = (AF_INET and 0xFF).toByte()
            bytes[1] = ((AF_INET shr 8) and 0xFF).toByte()
            bytes[2] = ((linux.port shr 8) and 0xFF).toByte()
            bytes[3] = (linux.port and 0xFF).toByte()
            for (i in 0 until 4) bytes[4 + i] = ((linux.lo shr (24 - 8 * i)) and 0xFF).toByte()
            sizeOf<sockaddr_in>().convert()
        }
        AddressFamily.IPv6 -> {
            bytes[0] = (AF_INET6 and 0xFF).toByte()
            bytes[1] = ((AF_INET6 shr 8) and 0xFF).toByte()
            bytes[2] = ((linux.port shr 8) and 0xFF).toByte()
            bytes[3] = (linux.port and 0xFF).toByte()
            // offset 4..7 sin6_flowinfo = 0 (already zeroed)
            for (i in 0 until 8) bytes[8 + i] = ((linux.hi shr (56 - 8 * i)) and 0xFF).toByte()
            for (i in 0 until 8) bytes[16 + i] = ((linux.lo shr (56 - 8 * i)) and 0xFF).toByte()
            sizeOf<sockaddr_in6>().convert()
        }
    }
}

/**
 * Decode a kernel-filled `sockaddr` (from `recvmsg`/`getsockname`) into a [LinuxSocketAddress],
 * extracting the endpoint into primitives immediately so the caller may reuse the source storage. The
 * numeric host string is produced via `inet_ntop` for inspectability (ICE / logging). Returns `null`
 * for a family other than IPv4/IPv6.
 */
@ExperimentalDatagramApi
internal fun sockaddrToLinuxSocketAddress(addr: CPointer<sockaddr>): LinuxSocketAddress? {
    val bytes = addr.reinterpret<ByteVar>()
    val family = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    val port = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
    return when (family) {
        AF_INET -> {
            var lo = 0L
            for (i in 0 until 4) lo = (lo shl 8) or (bytes[4 + i].toLong() and 0xFF)
            LinuxSocketAddress(inetNtop(addr, AF_INET, addrOffset = 4), port, AddressFamily.IPv4, 0L, lo)
        }
        AF_INET6 -> {
            var hi = 0L
            var lo = 0L
            for (i in 0 until 8) hi = (hi shl 8) or (bytes[8 + i].toLong() and 0xFF)
            for (i in 0 until 8) lo = (lo shl 8) or (bytes[16 + i].toLong() and 0xFF)
            LinuxSocketAddress(inetNtop(addr, AF_INET6, addrOffset = 8), port, AddressFamily.IPv6, hi, lo)
        }
        else -> null
    }
}

/** Format the numeric host of [addr] via `inet_ntop`, reading the address bytes at [addrOffset]. */
@ExperimentalDatagramApi
private fun inetNtop(
    addr: CPointer<sockaddr>,
    af: Int,
    addrOffset: Int,
): String =
    memScoped {
        val out = allocArray<ByteVar>(INET6_ADDRSTRLEN)
        val src = addr.reinterpret<ByteVar>() + addrOffset
        inet_ntop(af, src, out, INET6_ADDRSTRLEN.convert())
        out.toKString()
    }

/**
 * Parse a numeric IP [host] into a [LinuxSocketAddress] with no DNS (via `getaddrinfo` with
 * `AI_NUMERICHOST`). Used as the fallback when a foreign literal reaches [writeSockaddr].
 */
@ExperimentalDatagramApi
private fun parseNumericHost(
    host: String,
    port: Int,
): LinuxSocketAddress =
    resolveViaGetaddrinfo(host, port, numericOnly = true)
        ?: throw IllegalArgumentException("Not a valid numeric IP: '$host'")

/**
 * Resolve [host]:[port] through `getaddrinfo` into a [LinuxSocketAddress]. When [numericOnly] is set,
 * `AI_NUMERICHOST` skips any DNS lookup (the literal fast path). Returns `null` if resolution fails.
 * Prefers the first IPv4 result, else the first IPv6, for loopback determinism.
 */
@ExperimentalDatagramApi
internal fun resolveViaGetaddrinfo(
    host: String,
    port: Int,
    numericOnly: Boolean,
): LinuxSocketAddress? =
    memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_DGRAM
        if (numericOnly) hints.ai_flags = AI_NUMERICHOST
        val resultPtr = allocPointerTo<addrinfo>()
        val rc = getaddrinfo(host, port.toString(), hints.ptr, resultPtr.ptr)
        if (rc != 0) return@memScoped null
        try {
            // Walk the list: prefer an IPv4 answer for deterministic loopback behavior, else first.
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
            chosen?.let { sockaddrToLinuxSocketAddress(it) }
        } finally {
            freeaddrinfo(resultPtr.value)
        }
    }

/**
 * The Linux hostname resolver installed into buffer-flow (RFC §10.1). Numeric literals resolve with no
 * lookup; hostnames perform real DNS via `getaddrinfo`, off the caller's dispatcher. The result is a
 * [LinuxSocketAddress] owning its resolved primitives for zero-alloc reuse as a send target.
 */
@ExperimentalDatagramApi
internal object LinuxSocketAddressResolver : SocketAddressResolver {
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
