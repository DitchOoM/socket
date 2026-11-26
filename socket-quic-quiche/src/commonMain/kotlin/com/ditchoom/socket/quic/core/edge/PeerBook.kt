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
 * address and by key, so a datagram's source and a reply's destination are converted once per peer and found in one
 * probe. A peer whose slot another took is converted again; only that allocates. Every slot starts as the channel's own
 * address, a true pair. The loop thread only.
 */
@LoopOnly
internal class PeerBook(
    first: SocketAddress,
) {
    private val firstKey = first.toPathKey()
    private val byAddress = Array(PEERS) { first }
    private val keyOfAddress = Array(PEERS) { firstKey }
    private val byKey = Array(PEERS) { firstKey }
    private val addressOfKey = Array(PEERS) { first }

    fun keyOf(address: SocketAddress): PathKey {
        val at = slotOf(address.hashCode())
        if (byAddress[at] == address) return keyOfAddress[at]
        val key = address.toPathKey()
        remember(address, key)
        return key
    }

    fun addressOf(key: PathKey): SocketAddress {
        val at = slotOf(key.hashCode())
        if (byKey[at] == key) return addressOfKey[at]
        val address = key.toSocketAddress()
        remember(address, key)
        return address
    }

    private fun remember(
        address: SocketAddress,
        key: PathKey,
    ) {
        val byA = slotOf(address.hashCode())
        byAddress[byA] = address
        keyOfAddress[byA] = key
        val byK = slotOf(key.hashCode())
        byKey[byK] = key
        addressOfKey[byK] = address
    }

    private fun slotOf(hash: Int): Int = (hash xor (hash ushr HASH_SPREAD)) and (PEERS - 1)

    private companion object {
        /** Slots per table, a power of two. */
        const val PEERS = 256
        const val HASH_SPREAD = 16
    }
}
