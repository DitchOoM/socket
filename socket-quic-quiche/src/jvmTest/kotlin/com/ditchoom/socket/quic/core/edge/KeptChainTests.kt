package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import com.ditchoom.socket.quic.MAX_CERT_CAPACITY
import com.ditchoom.socket.quic.MAX_CHAIN_LENGTH
import com.ditchoom.socket.quic.PeerCertPosition
import com.ditchoom.socket.quic.core.conn.ChainGap
import com.ditchoom.socket.quic.core.conn.PeerChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A client's kept chain is read only while kept whole, freed exactly once whichever of its arrival and its letting go
 * comes first, and every way of not being kept whole reads as the rejection a check of it comes to.
 */
class KeptChainTests {
    private val factory = FreeCountingFactory(BufferFactory.deterministic(), setOf(FreeCountingFactory.Site.Core))

    @Test
    fun aKeptChainIsReadAndFreedOnceWhenLetGo() {
        val kept = KeptChain()
        kept.keep(PeerChain.Copied(listOf(certificate(LEAF))))
        assertEquals(LEAF, kept.copy(PeerCertPosition.Leaf, BufferFactory.deterministic().allocate(LEAF), LEAF))
        kept.letGo()
        kept.letGo()
        assertEquals(listOf(1), factory.edgeBuffers().map { it.frees.get() }, "freed once, however often let go")
    }

    @Test
    fun aChainThatArrivesAfterItWasLetGoIsFreedOnArrival() {
        val kept = KeptChain()
        kept.letGo()
        kept.keep(PeerChain.Copied(listOf(certificate(LEAF))))
        assertEquals(listOf(1), factory.edgeBuffers().map { it.frees.get() })
        assertEquals(ServerCertificateRejection.NoPeerCertificate, rejectionOf(kept), "a chain let go is never read")
    }

    @Test
    fun aChainReadAfterItWasLetGoIsRefused() {
        val kept = KeptChain()
        kept.keep(PeerChain.Copied(listOf(certificate(LEAF))))
        kept.letGo()
        assertEquals(ServerCertificateRejection.NoPeerCertificate, rejectionOf(kept))
    }

    @Test
    fun everyChainNotKeptWholeReadsAsItsRejection() {
        assertEquals(ServerCertificateRejection.NoPeerCertificate, rejectionOf(KeptChain()), "none delivered")
        assertEquals(ServerCertificateRejection.NoPeerCertificate, rejectionOf(kept(PeerChain.Copied(emptyList()))), "none presented")
        assertEquals(ServerCertificateRejection.NoPeerCertificate, rejectionOf(kept(PeerChain.NotCopied)), "none copied")
        assertEquals<ServerCertificateRejection>(
            ServerCertificateRejection.ChainTooLong(MAX_CHAIN_LENGTH),
            rejectionOf(kept(PeerChain.Incomplete(ChainGap.TooLong))),
        )
        assertEquals<ServerCertificateRejection>(
            ServerCertificateRejection.CertificateTooLarge(MAX_CERT_CAPACITY + 1, MAX_CERT_CAPACITY),
            rejectionOf(kept(PeerChain.Incomplete(ChainGap.TooLarge(MAX_CERT_CAPACITY + 1)))),
        )
        assertEquals(ServerCertificateRejection.NoPeerCertificate, rejectionOf(kept(PeerChain.Incomplete(ChainGap.Unreadable))))
    }

    private fun kept(chain: PeerChain) = KeptChain().also { it.keep(chain) }

    private fun rejectionOf(kept: KeptChain): ServerCertificateRejection =
        assertFailsWith<ServerCertificateRejectedException> {
            kept.copy(PeerCertPosition.Leaf, BufferFactory.deterministic().allocate(LEAF), LEAF)
        }.rejection

    /** A certificate of [size] bytes, from a core frame of the counting factory, so its frees are counted. */
    private fun certificate(size: Int) = factory.allocate(size)

    private companion object {
        const val LEAF = 64
    }
}
