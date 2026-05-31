package com.ditchoom.socket.quic

/**
 * Allocation-free identity for a network path's local or peer address, used by the
 * multi-socket migration driver (slice 3) to route each outgoing datagram to the
 * socket bound to a given local address and to match path-event addresses.
 *
 * [hi]/[lo] hold the raw address bits (IPv4 packs into [lo] with [hi] == 0; IPv6
 * fills both). Byte order is unspecified on purpose — a [PathKey] is only ever
 * compared, never reconstructed into an address — so two decodes of the same
 * sockaddr are equal and two distinct addresses differ. No `ByteArray` (the
 * production no-ByteArray rule); a value-based [data class] gives correct equality
 * for use as a `Map` key.
 */
data class PathKey(
    /** 4 (IPv4), 6 (IPv6), or 0 (unknown). */
    val family: Int,
    val port: Int,
    val hi: Long,
    val lo: Long,
)

/**
 * Decode the sockaddr at native pointer [addr] into an allocation-free [PathKey].
 * Both sides of a routing comparison decode through the same [QuicheApi] backend,
 * so equality is consistent regardless of the unspecified byte order in [PathKey].
 */
fun QuicheApi.decodePathKey(addr: Long): PathKey =
    when (sockAddrFamily(addr)) {
        4 -> PathKey(family = 4, port = sockAddrPort(addr), hi = 0L, lo = sockAddrV4(addr))
        6 -> PathKey(family = 6, port = sockAddrPort(addr), hi = sockAddrV6Hi(addr), lo = sockAddrV6Lo(addr))
        else -> PathKey(family = 0, port = 0, hi = 0L, lo = 0L)
    }
