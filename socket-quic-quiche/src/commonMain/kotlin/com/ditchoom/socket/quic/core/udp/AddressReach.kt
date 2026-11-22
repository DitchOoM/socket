package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey

/** Where an address sits from this host, as local-network protection draws the line. */
internal enum class AddressReach {
    /** This host itself. */
    Loopback,

    /** On a network attached to this host: inside an interface's prefix, link-local, multicast or limited broadcast. */
    OnLink,

    /** A private range (RFC 1918, RFC 4193) outside every attached prefix: reached through a router or a tunnel. */
    Private,

    Beyond,
}

/** The network of one interface address: the first [length] bits of [network] are significant. */
internal class AttachedPrefix(
    val network: PathKey,
    val length: Int,
)

/** Where this address (big-endian, as `toPathKey` builds it) sits, given the networks [attached] to this host. */
internal fun PathKey.reachFrom(attached: List<AttachedPrefix>): AddressReach =
    when (family) {
        4 -> v4Reach(lo and V4_MASK, attached)
        6 ->
            if (hi == 0L && lo ushr V4_BITS == V4_MAPPED) {
                v4Reach(lo and V4_MASK, attached)
            } else {
                v6Reach(attached)
            }
        else -> AddressReach.Beyond
    }

/** The networks this host's interfaces are attached to now; none when they cannot be read. */
internal expect fun attachedPrefixes(): List<AttachedPrefix>

private fun v4Reach(
    v4: Long,
    attached: List<AttachedPrefix>,
): AddressReach =
    when {
        v4 ushr V4_LOOPBACK_SHIFT == V4_LOOPBACK -> AddressReach.Loopback
        v4 ushr V4_LINK_LOCAL_SHIFT == V4_LINK_LOCAL -> AddressReach.OnLink
        v4 ushr V4_MULTICAST_SHIFT == V4_MULTICAST -> AddressReach.OnLink
        v4 == V4_MASK -> AddressReach.OnLink
        attached.any { it.network.family == 4 && it.containsV4(v4) } -> AddressReach.OnLink
        v4 ushr V4_PRIVATE_10_SHIFT == V4_PRIVATE_10 -> AddressReach.Private
        v4 ushr V4_PRIVATE_172_SHIFT == V4_PRIVATE_172 -> AddressReach.Private
        v4 ushr V4_PRIVATE_192_SHIFT == V4_PRIVATE_192 -> AddressReach.Private
        else -> AddressReach.Beyond
    }

private fun PathKey.v6Reach(attached: List<AttachedPrefix>): AddressReach =
    when {
        hi == 0L && lo == 1L -> AddressReach.Loopback
        hi ushr V6_LINK_LOCAL_SHIFT == V6_LINK_LOCAL -> AddressReach.OnLink
        hi ushr V6_MULTICAST_SHIFT == V6_MULTICAST -> AddressReach.OnLink
        attached.any { it.network.family == 6 && it.containsV6(hi, lo) } -> AddressReach.OnLink
        hi ushr V6_UNIQUE_LOCAL_SHIFT == V6_UNIQUE_LOCAL -> AddressReach.Private
        else -> AddressReach.Beyond
    }

/** A prefix of no length would put every address on the link: only a real network counts. */
private fun AttachedPrefix.containsV4(v4: Long): Boolean {
    if (length !in 1..V4_BITS) return false
    val shift = V4_BITS - length
    return (network.lo and V4_MASK) ushr shift == v4 ushr shift
}

private fun AttachedPrefix.containsV6(
    hi: Long,
    lo: Long,
): Boolean {
    if (length !in 1..V6_BITS) return false
    if (length <= HALF_BITS) return network.hi ushr (HALF_BITS - length) == hi ushr (HALF_BITS - length)
    val shift = V6_BITS - length
    return network.hi == hi && network.lo ushr shift == lo ushr shift
}

private const val V4_BITS = 32
private const val V6_BITS = 128
private const val HALF_BITS = 64
private const val V4_MASK = 0xFFFF_FFFFL
private const val V4_MAPPED = 0xFFFFL
private const val V4_LOOPBACK_SHIFT = 24
private const val V4_LOOPBACK = 127L
private const val V4_LINK_LOCAL_SHIFT = 16
private const val V4_LINK_LOCAL = 0xA9FEL
private const val V4_MULTICAST_SHIFT = 28
private const val V4_MULTICAST = 0xEL
private const val V4_PRIVATE_10_SHIFT = 24
private const val V4_PRIVATE_10 = 10L
private const val V4_PRIVATE_172_SHIFT = 20
private const val V4_PRIVATE_172 = 0xAC1L
private const val V4_PRIVATE_192_SHIFT = 16
private const val V4_PRIVATE_192 = 0xC0A8L
private const val V6_LINK_LOCAL_SHIFT = 54
private const val V6_LINK_LOCAL = 0x3FAL
private const val V6_MULTICAST_SHIFT = 56
private const val V6_MULTICAST = 0xFFL
private const val V6_UNIQUE_LOCAL_SHIFT = 57
private const val V6_UNIQUE_LOCAL = 0x7EL
