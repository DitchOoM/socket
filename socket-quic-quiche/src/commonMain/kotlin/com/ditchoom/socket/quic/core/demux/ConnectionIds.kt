package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.pool.LongObjectMap
import com.ditchoom.socket.quic.core.udp.NativeBytes

/**
 * A connection id read out of a datagram: up to 20 bytes held as three longs and a length, so comparing and hashing
 * it allocates nothing. Overwritten for each datagram; loop thread only.
 */
@LoopOnly
internal class CidRead {
    var c0 = 0L
        private set
    var c1 = 0L
        private set
    var c2 = 0L
        private set
    var length = 0
        private set

    /** The id's hash: what [RouteTable] keys on. */
    var hash = 0L
        private set

    /** Reads [length] bytes of [from] starting at [offset]; [length] is at most [MAX_CID_LENGTH]. */
    fun read(
        from: NativeBytes,
        offset: Int,
        length: Int,
    ) {
        var a = 0L
        var b = 0L
        var c = 0L
        for (i in 0 until length) {
            val byte = from.byteAt(offset + i).toLong()
            when {
                i < WORD -> a = (a shl BITS) or byte
                i < 2 * WORD -> b = (b shl BITS) or byte
                else -> c = (c shl BITS) or byte
            }
        }
        set(a, b, c, length)
    }

    fun set(
        c0: Long,
        c1: Long,
        c2: Long,
        length: Int,
    ) {
        this.c0 = c0
        this.c1 = c1
        this.c2 = c2
        this.length = length
        var h = (c0 xor (c1 * MIX_1) xor (c2 * MIX_2) xor length.toLong()) * MIX_3
        h = h xor (h ushr FOLD)
        hash = h
    }

    /** The [index]th byte of the id, as 0..255. */
    fun byteAt(index: Int): Int {
        val word = index / WORD
        val inWord = minOf(WORD, length - WORD * word)
        val bits =
            when (word) {
                0 -> c0
                1 -> c1
                else -> c2
            }
        return ((bits ushr ((inWord - 1 - index % WORD) * BITS)) and BYTE).toInt()
    }

    companion object {
        /** The longest id a peer may choose (RFC 9000 section 17.2). */
        const val MAX_CID_LENGTH = 20
        private const val WORD = 8
        private const val BITS = 8
        private const val BYTE = 0xFFL
        private const val MIX_1 = -0x61c8864680b583ebL
        private const val MIX_2 = 0x2545F4914F6CDD1DL
        private const val MIX_3 = -0x4b472d9bf2a3c4d5L
        private const val FOLD = 29
    }
}

/** What a [RouteTable] answers: the route of an id, or that it has none. */
internal sealed interface RouteLink<out T> {
    data object End : RouteLink<Nothing>
}

/** The route after one in its hash chain: an intrusive link only its [RouteTable] writes. */
@LoopOnly
internal class ChainLink<T> {
    var next: RouteLink<T> = RouteLink.End
        private set

    fun linkTo(following: RouteLink<T>) {
        next = following
    }
}

/** One connection id of a connection, and the next id whose hash is the same. */
internal class Route<T>(
    val hash: Long,
    private val c0: Long,
    private val c1: Long,
    private val c2: Long,
    private val length: Int,
    val owner: T,
) : RouteLink<T> {
    val chain = ChainLink<T>()

    val next: RouteLink<T> get() = chain.next

    fun linkTo(following: RouteLink<T>) = chain.linkTo(following)

    fun matches(cid: CidRead): Boolean = length == cid.length && c0 == cid.c0 && c1 == cid.c1 && c2 == cid.c2

    /** The same id, routed to [to]. */
    fun <U> retargeted(to: U): Route<U> = Route(hash, c0, c1, c2, length, to)
}

/**
 * Connection id to owner, for one loop's thread alone. Keyed by the id's 64-bit hash, so a lookup allocates nothing;
 * ids whose hashes collide chain, and every route is checked against the full id before it is believed.
 */
@LoopOnly
internal class RouteTable<T> {
    private val table = LongObjectMap<RouteLink<T>>(RouteLink.End)

    val size: Int get() = table.size

    /** The route of [cid], or [RouteLink.End]. */
    fun find(cid: CidRead): RouteLink<T> {
        var at = table.getOrDefault(cid.hash, RouteLink.End)
        while (at is Route<T>) {
            if (at.matches(cid)) return at
            at = at.next
        }
        return RouteLink.End
    }

    /** Routes [cid] to [owner]. The id must not be routed already. */
    fun add(
        cid: CidRead,
        owner: T,
    ): Route<T> = insert(Route(cid.hash, cid.c0, cid.c1, cid.c2, cid.length, owner))

    /** Routes [route]'s id; it must not be routed already. */
    fun insert(route: Route<T>): Route<T> {
        route.linkTo(table.getOrDefault(route.hash, RouteLink.End))
        table[route.hash] = route
        return route
    }

    fun remove(route: Route<T>) {
        val head = table.getOrDefault(route.hash, RouteLink.End)
        if (head === route) {
            when (val following = route.next) {
                is RouteLink.End -> table.remove(route.hash)
                is Route<T> -> table[route.hash] = following
            }
        } else {
            var at = head
            while (at is Route<T>) {
                if (at.next === route) {
                    at.linkTo(route.next)
                    break
                }
                at = at.next
            }
        }
        route.linkTo(RouteLink.End)
    }
}
