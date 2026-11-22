package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.MAX_CERT_CAPACITY
import com.ditchoom.socket.quic.MAX_CHAIN_LENGTH
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.edge.FreeCountingFactory
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.testing.nativeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The copy of the peer's chain a completed handshake takes is whole or says why not: a chain longer than a client
 * reads and a certificate larger than it reads are told apart from a chain quiche handed over short, and whatever ends
 * the copy, nothing it allocated stays held unless the whole chain was copied.
 */
class PeerChainTests {
    private val factory = FreeCountingFactory(BufferFactory.deterministic(), setOf(FreeCountingFactory.Site.Core))

    @Test
    fun aChainOfOneCertificateMoreThanAClientReadsIsTooLong() {
        val chain = copyOf(StubQuicheApi().apply { peerCertChainLens = List(MAX_CHAIN_LENGTH + 1) { CERT } })
        assertIs<ChainGap.TooLong>(assertIs<PeerChain.Incomplete>(chain).gap)
        assertEquals(0L, factory.heldBytes(), "what was copied before the chain proved too long was freed")
    }

    @Test
    fun aChainOfExactlyAsManyCertificatesAsAClientReadsIsCopied() {
        val chain = copyOf(StubQuicheApi().apply { peerCertChainLens = List(MAX_CHAIN_LENGTH) { CERT } })
        assertEquals(MAX_CHAIN_LENGTH, assertIs<PeerChain.Copied>(chain).size())
        chain.free()
        assertEquals(0L, factory.heldBytes())
    }

    @Test
    fun aLeafLargerThanAClientReadsKeepsItsLength() {
        val chain = copyOf(StubQuicheApi().apply { peerCertChainLens = listOf(MAX_CERT_CAPACITY + 1) })
        val gap = assertIs<ChainGap.TooLarge>(assertIs<PeerChain.Incomplete>(chain).gap)
        assertEquals(MAX_CERT_CAPACITY + 1, gap.length)
    }

    @Test
    fun aCertificateQuicheHandsOverShortIsUnreadable() {
        val stub = StubQuicheApi().apply { peerCertChainLens = listOf(CERT, CERT) }
        val short =
            object : QuicheApi by stub {
                override fun connPeerCertChainAt(
                    conn: QuicheConn,
                    index: Int,
                    buf: Long,
                    bufLen: Int,
                ): Int = if (index == 1 && bufLen > 0) CERT - 1 else stub.connPeerCertChainAt(conn, index, buf, bufLen)
            }
        assertIs<ChainGap.Unreadable>(assertIs<PeerChain.Incomplete>(copyOf(short)).gap)
        assertEquals(0L, factory.heldBytes(), "the leaf copied before the short read was freed")
    }

    @Test
    fun aCopyThatThrowsPartWayFreesWhatItAlreadyCopied() {
        val stub = StubQuicheApi().apply { peerCertChainLens = listOf(CERT, CERT, CERT) }
        val failing =
            object : QuicheApi by stub {
                override fun connPeerCertChainAt(
                    conn: QuicheConn,
                    index: Int,
                    buf: Long,
                    bufLen: Int,
                ): Int = if (index == 1 && bufLen > 0) error("chain export failed") else stub.connPeerCertChainAt(conn, index, buf, bufLen)
            }
        assertFailsWith<IllegalStateException> { copyOf(failing) }
        assertTrue(factory.edgeBuffers().size >= 2, "the leaf and the certificate being read were allocated")
        assertEquals(0L, factory.heldBytes(), "nothing the failed copy allocated is held")
    }

    private fun copyOf(api: QuicheApi): PeerChain =
        peerChainOf(ChainSource.Copied(factory), QuicheConnRef(api, QuicheConn(1L)), nativeBytes(16))

    private companion object {
        const val CERT = 512
    }
}
