package com.ditchoom.socket.quic

/**
 * Allocation-free identity for a network path's local or peer address, used by the
 * multi-socket migration driver (slice 3) to route each outgoing datagram to the
 * socket bound to a given local address and to match path-event addresses.
 *
 * Sealed over the address family instead of carrying it as an `Int` tag: [V4]/[V6] can only ever
 * hold the fields their family actually has, and [Undecoded] — the sockaddr-decode-failed case a
 * bare `family = 0` used to encode in-band — is its own object rather than a `V4`-shaped value
 * whose zeroed fields happened to mean "not real". [hi]/[lo] on [V6] (and [V4]'s [V4.addr]) hold the
 * raw address bits in an unspecified byte order — a [PathKey] is compared for identity, not decoded
 * — so two decodes of the same sockaddr are equal and two distinct addresses differ.
 *
 * That "compared, never reconstructed" claim is true for routing (the driver's `paths` map, egress
 * channel selection) but not universally: a channel backend that must hand a raw send target to the
 * OS — `PathKey.toInetSocketAddress()` on the JVM/Android send path, `PathKey.toSockAddrBuffer()` on
 * Linux's io_uring send path — does reconstruct a real sockaddr from a [PathKey]'s fields, because
 * `sendInfo.to`'s decoded [PathKey] is the only thing available at that boundary once egress must
 * follow a migrated peer. Those two call sites are the deliberate exception, not a hole in the
 * invariant every other consumer relies on. No `ByteArray` (the production no-ByteArray rule); the
 * `data class` variants give correct equality for use as a `Map` key.
 */
sealed interface PathKey {
    /** IPv4: [addr] packs the 4 address bytes. */
    data class V4(
        val port: Int,
        val addr: Long,
    ) : PathKey

    /** IPv6: [hi]/[lo] together pack the 16 address bytes. */
    data class V6(
        val port: Int,
        val hi: Long,
        val lo: Long,
    ) : PathKey

    /** decodePathKey could not read an address family — a backend/test double that decodes nothing. */
    data object Undecoded : PathKey
}

/**
 * Decode the sockaddr at native pointer [addr] into an allocation-free [PathKey].
 * Both sides of a routing comparison decode through the same [QuicheApi] backend,
 * so equality is consistent regardless of the unspecified byte order in [PathKey].
 */
fun QuicheApi.decodePathKey(addr: Long): PathKey =
    when (sockAddrFamily(addr)) {
        4 -> PathKey.V4(port = sockAddrPort(addr), addr = sockAddrV4(addr))
        6 -> PathKey.V6(port = sockAddrPort(addr), hi = sockAddrV6Hi(addr), lo = sockAddrV6Lo(addr))
        else -> PathKey.Undecoded
    }
