package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId

/**
 * Which local addresses each link carries, keyed by the [NetworkId] [NetworkMonitor.state] names that
 * link with — the value side of [NetworkMonitor.linkAddresses].
 *
 * It answers one question: given the local address a socket is bound on, which link is that socket
 * on. The platform's default route can move before the monitor names the new link, so a socket opened
 * on the default route is not necessarily on the link the monitor names; its bound address is what
 * says where it is.
 */
sealed interface LinkAddresses {
    /** This monitor does not report which addresses a link carries. */
    data object NotReported : LinkAddresses

    /**
     * The links this monitor knows and the addresses each one carries, as of its latest observation. A
     * link the monitor does not know is absent, so an address no entry holds is claimed by no link the
     * monitor knows.
     */
    data class Reported(
        val byLink: Map<NetworkId, Set<NumericAddress>>,
    ) : LinkAddresses
}

/** Which link, if any, carries a local address — the answer [LinkAddresses.ownerOf] gives. */
sealed interface AddressOwner {
    /** The monitor does not report addresses, so it cannot say. */
    data object NotReported : AddressOwner

    /** The monitor reports addresses and none of its links carries this one. */
    data object Unclaimed : AddressOwner

    /** The link [id] carries the address. */
    data class Link(
        val id: NetworkId,
    ) : AddressOwner
}

/** The link that carries [address]. */
fun LinkAddresses.ownerOf(address: NumericAddress): AddressOwner =
    when (this) {
        LinkAddresses.NotReported -> AddressOwner.NotReported
        is LinkAddresses.Reported ->
            byLink.entries
                .firstOrNull { address in it.value }
                ?.let { AddressOwner.Link(it.key) }
                ?: AddressOwner.Unclaimed
    }

/**
 * An IP address as its numeric value, so every spelling of one address is one value: `::1` and
 * `0:0:0:0:0:0:0:1`, an IPv6 literal with and without its `%zone`, and an IPv4 address and its
 * IPv4-mapped IPv6 form (`::ffff:192.0.2.7`, which a dual-stack socket reports) are each equal.
 */
sealed interface NumericAddress {
    /** An IPv4 address; [bits] is the address in network order, most significant octet first. */
    data class V4(
        val bits: Int,
    ) : NumericAddress {
        override fun toString(): String = (0..3).joinToString(".") { ((bits ushr (24 - 8 * it)) and 0xff).toString() }
    }

    /** An IPv6 address that is not IPv4-mapped; [hi] and [lo] are its upper and lower 64 bits. */
    data class V6(
        val hi: Long,
        val lo: Long,
    ) : NumericAddress {
        override fun toString(): String =
            (0..7).joinToString(":") { group ->
                val half = if (group < 4) hi else lo
                ((half ushr (48 - 16 * (group % 4))) and 0xffff).toString(16)
            }
    }

    companion object {
        /** Parse a numeric IPv4 or IPv6 literal, as a platform prints one. */
        fun parse(literal: String): ParsedAddress =
            when (val address = parseV6OrV4(literal.substringBefore('%'))) {
                null -> ParsedAddress.NotNumeric(literal)
                else -> ParsedAddress.Address(address)
            }
    }
}

/** What [NumericAddress.parse] made of a literal. */
sealed interface ParsedAddress {
    /** The literal is the numeric address [address]. */
    data class Address(
        val address: NumericAddress,
    ) : ParsedAddress

    /** [literal] is not a numeric IPv4 or IPv6 address (a host name, or malformed). */
    data class NotNumeric(
        val literal: String,
    ) : ParsedAddress
}

/** The numeric addresses among [literals]; a literal that is not one names no address and is left out. */
internal fun numericAddressesOf(literals: Iterable<String>): Set<NumericAddress> =
    literals.mapNotNullTo(LinkedHashSet()) {
        when (val parsed = NumericAddress.parse(it)) {
            is ParsedAddress.Address -> parsed.address
            is ParsedAddress.NotNumeric -> null
        }
    }

private fun parseV6OrV4(text: String): NumericAddress? =
    if (':' in
        text
    ) {
        parseV6(text)
    } else {
        parseV4Bits(text)?.let { NumericAddress.V4(it) }
    }

private fun parseV4Bits(text: String): Int? {
    val octets = text.split('.')
    if (octets.size != 4) return null
    var bits = 0
    for (octet in octets) {
        if (octet.isEmpty() || octet.length > 3 || !octet.all { it in '0'..'9' }) return null
        val value = octet.toInt()
        if (value > 255) return null
        bits = (bits shl 8) or value
    }
    return bits
}

private fun parseV6(text: String): NumericAddress? {
    val halves = text.split("::")
    if (halves.size > 2) return null
    val head = groupsOf(halves[0]) ?: return null
    val tail = if (halves.size == 2) groupsOf(halves[1]) ?: return null else emptyList()
    val groups =
        when (halves.size) {
            1 -> if (head.size == 8) head else return null
            else -> if (head.size + tail.size <= 7) head + List(8 - head.size - tail.size) { 0 } + tail else return null
        }
    var hi = 0L
    var lo = 0L
    for (i in 0..3) hi = (hi shl 16) or groups[i].toLong()
    for (i in 4..7) lo = (lo shl 16) or groups[i].toLong()
    return if (hi == 0L && (lo ushr 32) == 0xffffL) NumericAddress.V4(lo.toInt()) else NumericAddress.V6(hi, lo)
}

/** The 16-bit groups of one side of a `::`, a trailing dotted IPv4 counting as two. */
private fun groupsOf(side: String): List<Int>? {
    if (side.isEmpty()) return emptyList()
    val parts = side.split(':')
    val groups = mutableListOf<Int>()
    parts.forEachIndexed { index, part ->
        if (index == parts.lastIndex && '.' in part) {
            val v4 = parseV4Bits(part) ?: return null
            groups += (v4 ushr 16) and 0xffff
            groups += v4 and 0xffff
        } else {
            if (part.isEmpty() || part.length > 4) return null
            groups += part.toIntOrNull(16)?.takeIf { part.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } } ?: return null
        }
    }
    return groups
}
