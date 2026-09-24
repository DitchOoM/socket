package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.testkit.osnet.OsLink
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TracePath
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Rewrites every IP address a trace carries to a documentation address, so a window cut from a walk
 * can be committed to a public repository.
 *
 * A walk trace names the phone's own addresses in four places: the path token on every datagram
 * ([TracePath], whose bits *are* the local address), a [TraceEvent.PathState]'s local host, the
 * links of a [TraceEvent.OsNet], and whatever an [TraceEvent.Error] message quotes. All four are
 * rewritten through one table, so the same address reads the same everywhere in a trace and two
 * distinct addresses stay distinct.
 *
 * IPv4 goes to RFC 5737's three blocks and IPv6 to RFC 3849's `2001:db8::/32`, in order of first
 * appearance. An address already in those ranges is kept as it is and never handed out again, which
 * makes a redacted trace a fixed point: [redact] over its own output changes nothing, and that is
 * what [WalkFixtureRegenerationTests] checks every committed window against.
 *
 * The cost: [com.ditchoom.socket.testkit.osnet.OsNetworkFacts] derives each link's address *scope*
 * from its literals, so a carrier-NAT or link-local address reads back as global once redacted. The
 * probe log's `OS-NET` lines, which stay with the walk, keep the true scopes.
 */
internal object TraceAddressRedaction {
    /** [events] with every address rewritten through one table built over all of them. */
    fun redact(events: List<TraceEvent>): List<TraceEvent> {
        val table = Table(events.flatMap(::addressesIn).filter(::isDocumentation).toSet())
        return events.map { table.rewrite(it) }
    }

    /** Every IP address [event] carries, in any of the four places a trace can hold one. */
    fun addressesIn(event: TraceEvent): List<InetAddress> =
        when (event) {
            is TraceEvent.DgramIn -> listOfNotNull(event.path?.let(::addressOf))
            is TraceEvent.DgramOut -> listOfNotNull(event.path?.let(::addressOf))
            is TraceEvent.PathState -> listOfNotNull(event.localHost?.let(::literal))
            is TraceEvent.OsNet -> event.facts.links.flatMap { link -> link.addresses.mapNotNull(::literal) }
            is TraceEvent.Error -> literalsIn(event.message)
            is TraceEvent.StreamLoss, is TraceEvent.State, is TraceEvent.Migration, is TraceEvent.Silence,
            is TraceEvent.Stats, is TraceEvent.Net, is TraceEvent.NetGap, is TraceEvent.NetCapability,
            is TraceEvent.Liveness, is TraceEvent.QlogRefused, is TraceEvent.TrafficSecretsRefused,
            -> emptyList()
        }

    /** Every IP literal in free text: dotted quads, and colon-separated hex runs that parse as IPv6. */
    fun literalsIn(text: String): List<InetAddress> = LITERAL.findAll(text).mapNotNull { literal(it.value) }.toList()

    /** RFC 5737 (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24) or RFC 3849 (2001:db8::/32). */
    fun isDocumentation(address: InetAddress): Boolean {
        val b = address.address
        return when (address) {
            is Inet4Address -> V4_BLOCKS.any { it[0] == b[0] && it[1] == b[1] && it[2] == b[2] }
            is Inet6Address -> b[0] == 0x20.toByte() && b[1] == 0x01.toByte() && b[2] == 0x0d.toByte() && b[3] == 0xb8.toByte()
            else -> false
        }
    }

    private class Table(
        private val taken: Set<InetAddress>,
    ) {
        private val assigned = HashMap<InetAddress, InetAddress>()
        private var nextV4 = 0
        private var nextV6 = 1L

        fun rewrite(event: TraceEvent): TraceEvent =
            when (event) {
                is TraceEvent.DgramIn -> event.copy(path = event.path?.let(::rewrite))
                is TraceEvent.DgramOut -> event.copy(path = event.path?.let(::rewrite))
                is TraceEvent.PathState -> event.copy(localHost = event.localHost?.let(::rewriteText))
                is TraceEvent.OsNet ->
                    event.copy(
                        facts = event.facts.copy(links = event.facts.links.map { OsLink(it.name, it.addresses.map(::rewriteText)) }),
                    )
                is TraceEvent.Error -> event.copy(message = rewriteText(event.message))
                is TraceEvent.StreamLoss, is TraceEvent.State, is TraceEvent.Migration, is TraceEvent.Silence,
                is TraceEvent.Stats, is TraceEvent.Net, is TraceEvent.NetGap, is TraceEvent.NetCapability,
                is TraceEvent.Liveness, is TraceEvent.QlogRefused, is TraceEvent.TrafficSecretsRefused,
                -> event
            }

        private fun rewrite(path: TracePath): TracePath =
            when (val real = addressOf(path)) {
                null -> path
                else -> pathOf(path, map(real))
            }

        private fun rewriteText(text: String): String =
            LITERAL.replace(text) { match ->
                literal(match.value)?.let { map(it).hostAddress }
                    ?: match.value
            }

        private fun map(real: InetAddress): InetAddress = if (isDocumentation(real)) real else assigned.getOrPut(real) { allocate(real) }

        private fun allocate(real: InetAddress): InetAddress {
            while (true) {
                val candidate =
                    when (real) {
                        is Inet6Address -> v6(nextV6++)
                        else -> v4(nextV4++)
                    }
                if (candidate !in taken) return candidate
            }
        }

        private fun v4(index: Int): InetAddress {
            val block = V4_BLOCKS.getOrElse(index / 254) { error("more than ${254 * V4_BLOCKS.size} distinct IPv4 addresses in one trace") }
            return InetAddress.getByAddress(block + byteArrayOf((index % 254 + 1).toByte()))
        }

        private fun v6(index: Long): InetAddress =
            InetAddress.getByAddress(
                ByteArray(16).also { b ->
                    b[0] = 0x20
                    b[1] = 0x01
                    b[2] = 0x0d
                    b[3] = 0xb8.toByte()
                    for (i in 0 until 8) b[15 - i] = (index ushr (8 * i)).toByte()
                },
            )
    }

    /**
     * The address a [TracePath]'s bits spell, most significant byte first — the order every recorder
     * has written them in (an IPv4 path's `lo` is the dotted quad read left to right). A path of
     * unknown family carries no address.
     */
    private fun addressOf(path: TracePath): InetAddress? =
        when (path.family) {
            4 -> InetAddress.getByAddress(bytes(path.lo, 4))
            6 -> InetAddress.getByAddress(bytes(path.hi, 8) + bytes(path.lo, 8))
            else -> null
        }

    private fun pathOf(
        original: TracePath,
        address: InetAddress,
    ): TracePath {
        val b = address.address
        return when (address) {
            is Inet6Address -> original.copy(hi = long(b, 0, 8), lo = long(b, 8, 8))
            else -> original.copy(hi = 0, lo = long(b, 0, 4))
        }
    }

    private fun bytes(
        value: Long,
        count: Int,
    ): ByteArray = ByteArray(count) { i -> (value ushr (8 * (count - 1 - i))).toByte() }

    private fun long(
        b: ByteArray,
        from: Int,
        count: Int,
    ): Long = (from until from + count).fold(0L) { acc, i -> (acc shl 8) or (b[i].toLong() and 0xff) }

    /**
     * [text] as an IP literal, or nothing. Only strings made of hex digits, `.` and `:` are handed to
     * [InetAddress.getByName], which parses those without a lookup; anything else is not a literal.
     */
    private fun literal(text: String): InetAddress? {
        val bare = text.substringBefore('%')
        if (bare.isEmpty() || bare.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':') }) return null
        val dotted = bare.count { it == '.' } == 3 && bare.none { it == ':' } && bare.split('.').all { it.toIntOrNull() in 0..255 }
        val colons = bare.count { it == ':' } >= 2
        if (!dotted && !colons) return null
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }

    private val LITERAL = Regex("""(?<![\w.:])(?:\d{1,3}(?:\.\d{1,3}){3}|[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?:%\w+)?)(?![\w.:])""")

    private val V4_BLOCKS =
        listOf(
            byteArrayOf(192.toByte(), 0, 2),
            byteArrayOf(198.toByte(), 51, 100),
            byteArrayOf(203.toByte(), 0, 113),
        )
}
