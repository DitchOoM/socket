package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly

/**
 * The keys of the peers a socket hears from, so a datagram from a peer it heard recently builds no key. Two ways per set:
 * a key found or made moves to its set's first way and the one there to the second, so a set keeps the two peers last
 * heard; a peer past them builds its key again. [sets] is a power of two.
 */
@LoopOnly
internal class PeerKeys(
    sets: Int,
) {
    private val mask = sets - 1
    private val keys = Array(sets * WAYS) { EMPTY }

    fun keyOf(
        family: Int,
        port: Int,
        hi: Long,
        lo: Long,
    ): PathKey {
        val first = setOf(family, port, hi, lo) * WAYS
        val recent = keys[first]
        if (matches(recent, family, port, hi, lo)) return recent
        val older = keys[first + 1]
        val key = if (matches(older, family, port, hi, lo)) older else PathKey(family, port, hi, lo)
        keys[first + 1] = recent
        keys[first] = key
        return key
    }

    private fun setOf(
        family: Int,
        port: Int,
        hi: Long,
        lo: Long,
    ): Int {
        var x = (lo xor (hi * MIX) xor ((port.toLong() shl 8) or family.toLong())) * MIX
        x = x xor (x ushr 29)
        return x.toInt() and mask
    }

    private fun matches(
        key: PathKey,
        family: Int,
        port: Int,
        hi: Long,
        lo: Long,
    ): Boolean = key.lo == lo && key.hi == hi && key.port == port && key.family == family && key !== EMPTY

    companion object {
        /** A client's connected socket hears from one peer. */
        const val CLIENT_SETS = 1

        /** A server's socket hears from many: past this many recent peers, some build their key again. */
        const val SERVER_SETS = 1024

        private const val WAYS = 2
        private const val MIX = -0x61c8864680b583ebL

        /** What an unfilled way holds; it matches no datagram's sender. */
        private val EMPTY = PathKey(family = 0, port = 0, hi = 0L, lo = 0L)
    }
}
