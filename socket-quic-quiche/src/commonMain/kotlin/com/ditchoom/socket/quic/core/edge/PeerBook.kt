@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.quic.toSocketAddress

/**
 * The peers a lent server's io has heard from or sent to, each address with its [PathKey], in two open-addressed
 * tables keyed by address and by key, so a datagram's source and a reply's destination are converted once per peer.
 * A table holds up to [peers] peers whatever their hashes: a lookup probes from the hash's slot to the peer or to a
 * free slot, and no peer displaces another. A table that is full starts over empty, so each live peer is converted
 * once more after every [peers] new ones. The channel's own address marks a free slot and is answered without a probe.
 * The loop thread only.
 */
@LoopOnly
internal class PeerBook(
    private val first: SocketAddress,
    private val peers: Int = PEERS,
) {
    private val firstKey = first.toPathKey()
    private val mask = slotsFor(peers) - 1
    private val byAddress = Array(mask + 1) { first }
    private val keyOfAddress = Array(mask + 1) { firstKey }
    private val byKey = Array(mask + 1) { firstKey }
    private val addressOfKey = Array(mask + 1) { first }
    private var addressesHeld = 0
    private var keysHeld = 0
    private var conversions = 0L

    fun keyOf(address: SocketAddress): PathKey {
        if (address == first) return firstKey
        var at = slotOf(address.hashCode())
        while (byAddress[at] != first) {
            if (byAddress[at] == address) return keyOfAddress[at]
            at = (at + 1) and mask
        }
        conversions++
        val key = address.toPathKey()
        remember(address, key)
        return key
    }

    fun addressOf(key: PathKey): SocketAddress {
        if (key == firstKey) return first
        var at = slotOf(key.hashCode())
        while (byKey[at] != firstKey) {
            if (byKey[at] == key) return addressOfKey[at]
            at = (at + 1) and mask
        }
        conversions++
        val address = key.toSocketAddress()
        remember(address, key)
        return address
    }

    /** Tests: the conversions made so far, each one a new peer's or one forgotten when its table started over. */
    fun conversions(): Long = conversions

    private fun remember(
        address: SocketAddress,
        key: PathKey,
    ) {
        if (addressesHeld == peers) {
            byAddress.fill(first)
            keyOfAddress.fill(firstKey)
            addressesHeld = 0
        }
        var a = slotOf(address.hashCode())
        while (byAddress[a] != first && byAddress[a] != address) a = (a + 1) and mask
        if (byAddress[a] == first) addressesHeld++
        byAddress[a] = address
        keyOfAddress[a] = key
        if (keysHeld == peers) {
            byKey.fill(firstKey)
            addressOfKey.fill(first)
            keysHeld = 0
        }
        var k = slotOf(key.hashCode())
        while (byKey[k] != firstKey && byKey[k] != key) k = (k + 1) and mask
        if (byKey[k] == firstKey) keysHeld++
        byKey[k] = key
        addressOfKey[k] = address
    }

    private fun slotOf(hash: Int): Int = (hash xor (hash ushr HASH_SPREAD)) and mask

    private companion object {
        /** Peers each table holds before it starts over. */
        const val PEERS = 256
        const val HASH_SPREAD = 16

        /** The power of two above twice [peers], so a table is at most half full and every probe meets a free slot. */
        fun slotsFor(peers: Int): Int = (maxOf(peers, 1) * 2 - 1).takeHighestOneBit() shl 1
    }
}
