@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.quic.toSocketAddress

/**
 * The peers a lent server's io has heard from or sent to, each address with its [PathKey], in two tables hashed by
 * address and by key, so a datagram's source and a reply's destination are converted once per peer. Each table has
 * [buckets] buckets of [WAYS] ways: a lookup probes one bucket, and a new peer takes the way its bucket used least
 * recently, so two live peers that share a bucket never displace each other. Every way starts as the channel's own
 * address, a true pair. The loop thread only.
 */
@LoopOnly
internal class PeerBook(
    first: SocketAddress,
    private val buckets: Int = BUCKETS,
) {
    private val firstKey = first.toPathKey()
    private val byAddress = Array(buckets * WAYS) { first }
    private val keyOfAddress = Array(buckets * WAYS) { firstKey }
    private val byKey = Array(buckets * WAYS) { firstKey }
    private val addressOfKey = Array(buckets * WAYS) { first }

    /** Per bucket of each table, the way its next new peer takes: the one not used last. */
    private val nextByAddress = IntArray(buckets)
    private val nextByKey = IntArray(buckets)
    private var conversions = 0L

    fun keyOf(address: SocketAddress): PathKey {
        val bucket = bucketOf(address.hashCode())
        for (way in 0 until WAYS) {
            val at = bucket * WAYS + way
            if (byAddress[at] == address) return keyOfAddress[at].also { nextByAddress[bucket] = 1 - way }
        }
        conversions++
        val key = address.toPathKey()
        remember(address, key)
        return key
    }

    fun addressOf(key: PathKey): SocketAddress {
        val bucket = bucketOf(key.hashCode())
        for (way in 0 until WAYS) {
            val at = bucket * WAYS + way
            if (byKey[at] == key) return addressOfKey[at].also { nextByKey[bucket] = 1 - way }
        }
        conversions++
        val address = key.toSocketAddress()
        remember(address, key)
        return address
    }

    /** Tests: the conversions made so far, each one a new peer's or a displaced one's. */
    fun conversions(): Long = conversions

    private fun remember(
        address: SocketAddress,
        key: PathKey,
    ) {
        val byA = bucketOf(address.hashCode())
        val a = byA * WAYS + nextByAddress[byA]
        byAddress[a] = address
        keyOfAddress[a] = key
        nextByAddress[byA] = 1 - nextByAddress[byA]
        val byK = bucketOf(key.hashCode())
        val k = byK * WAYS + nextByKey[byK]
        byKey[k] = key
        addressOfKey[k] = address
        nextByKey[byK] = 1 - nextByKey[byK]
    }

    private fun bucketOf(hash: Int): Int = ((hash xor (hash ushr HASH_SPREAD)) and SIGN_CLEARED) % buckets

    private companion object {
        const val SIGN_CLEARED = 0x7FFF_FFFF

        /** Buckets per table: 256 peers in all. */
        const val BUCKETS = 128
        const val WAYS = 2
        const val HASH_SPREAD = 16
    }
}
