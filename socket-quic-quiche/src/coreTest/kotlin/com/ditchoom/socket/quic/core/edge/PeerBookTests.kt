@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.udp.UdpSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/** Live peers of a lent server's [PeerBook] are converted once each, however their hashes meet and however they alternate. */
class PeerBookTests {
    @Test
    fun twoPeersInOneBucketAreConvertedOnceEachInEitherDirection() =
        coreTest("PeerBookTests.twoPeersInOneBucketAreConvertedOnceEachInEitherDirection") {
            val book = PeerBook(UdpSocket.resolve("127.0.0.1", 1), peers = 2)
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

    @Test
    fun threePeersThatShareABucketAreConvertedOnceEach() =
        coreTest("PeerBookTests.threePeersThatShareABucketAreConvertedOnceEach") {
            // Three peers 8 ports apart start their probes on one slot of the book's 8, echoed in turn.
            val book = PeerBook(UdpSocket.resolve("127.0.0.1", 1), peers = 3)
            val peers = List(3) { UdpSocket.resolve("127.0.0.1", 40_000 + 8 * it) }
            repeat(ROUNDS) {
                for (p in peers) {
                    assertEquals(p.toPathKey(), book.keyOf(p))
                    assertEquals(p, book.addressOf(p.toPathKey()))
                }
            }
            assertEquals(3L, book.conversions(), "each peer was converted once, however many share a hash")
        }

    @Test
    fun aBookThatStartsOverStillPairsEveryPeer() =
        coreTest("PeerBookTests.aBookThatStartsOverStillPairsEveryPeer") {
            val book = PeerBook(UdpSocket.resolve("127.0.0.1", 1), peers = 4)
            val peers = List(10) { UdpSocket.resolve("127.0.0.1", 40_000 + it) }
            for (p in peers) assertEquals(p.toPathKey(), book.keyOf(p))
            // A forgotten peer's address is made again from its key: the same endpoint, not the same object.
            for (p in peers) {
                val made = book.addressOf(p.toPathKey())
                assertEquals(p.host to p.port, made.host to made.port)
                assertEquals(p.toPathKey(), book.keyOf(p))
            }
        }

    private companion object {
        const val ROUNDS = 100
    }
}
