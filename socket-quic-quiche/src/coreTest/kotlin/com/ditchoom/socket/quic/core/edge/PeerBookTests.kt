@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.udp.UdpSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two live peers that share a bucket of a lent server's [PeerBook] are converted once each, however they alternate. */
class PeerBookTests {
    @Test
    fun twoPeersInOneBucketAreConvertedOnceEachInEitherDirection() =
        coreTest("PeerBookTests.twoPeersInOneBucketAreConvertedOnceEachInEitherDirection") {
            // One bucket, so every peer shares it.
            val book = PeerBook(UdpSocket.resolve("127.0.0.1", 1), buckets = 1)
            val a = UdpSocket.resolve("127.0.0.1", 40_001)
            val b = UdpSocket.resolve("127.0.0.1", 40_002)
            repeat(ROUNDS) {
                assertEquals(a.toPathKey(), book.keyOf(a))
                assertEquals(b.toPathKey(), book.keyOf(b))
                assertEquals(a, book.addressOf(a.toPathKey()))
                assertEquals(b, book.addressOf(b.toPathKey()))
            }
            assertEquals(2L, book.conversions(), "each peer was converted once, on arrival, and its reply found it")
        }

    private companion object {
        const val ROUNDS = 100
    }
}
