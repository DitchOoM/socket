package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.LeafCert
import com.ditchoom.socket.quic.MAX_CERT_CAPACITY
import com.ditchoom.socket.quic.MAX_CHAIN_LENGTH
import com.ditchoom.socket.quic.PeerCertPosition
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.copiedLeaf
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress

/** Whether a connection's handshake completed, and with it what it settled. Carried by the connection's end. */
internal sealed interface HandshakeFate {
    data object NotCompleted : HandshakeFate

    class Completed(
        val facts: HandshakeFacts,
    ) : HandshakeFate
}

/**
 * What a completed handshake settled, read out of quiche once, inside, when it completed: the negotiated ALPN, how it
 * used a session ticket, and the peer's leaf certificate. Immutable; any thread reads it, the connection ended or not.
 */
internal class HandshakeFacts(
    val alpn: String,
    val resumption: QuicResumptionOutcome,
    val leaf: LeafCert,
)

/**
 * Whether a completed handshake copies the peer's certificate chain out of quiche: a client whose server is checked
 * after the handshake does, into memory of [Copied.factory]; one checked in the handshake, and a server, do not.
 */
internal sealed interface ChainSource {
    data object NotAsked : ChainSource

    class Copied(
        val factory: BufferFactory,
    ) : ChainSource
}

/** Why the peer's chain could not be copied whole. */
internal sealed interface ChainGap {
    /** More than [MAX_CHAIN_LENGTH] certificates. */
    data object TooLong : ChainGap

    /** A certificate of [length] bytes, over [MAX_CERT_CAPACITY]. */
    class TooLarge(
        val length: Int,
    ) : ChainGap

    /** quiche gave less than it said it held, or the factory's memory has no address quiche can write to. */
    data object Unreadable : ChainGap
}

/** The certificates the peer sent, as the completed handshake copied them out of quiche. Its holder frees it once. */
internal sealed interface PeerChain {
    /** Nothing checks the server after the handshake, so nothing was copied. */
    data object NotCopied : PeerChain

    /** Every certificate, leaf first, each in memory of its own; empty when the peer sent none. */
    class Copied(
        private val certificates: List<PlatformBuffer>,
    ) : PeerChain {
        /**
         * Copies the certificate at [position] into [der] at 0 when it fits in [capacity]; its length either way, or 0
         * past the end (quiche's snprintf contract, which every verification reads).
         */
        fun copy(
            position: PeerCertPosition,
            der: PlatformBuffer,
            capacity: Int,
        ): Int {
            val index =
                when (position) {
                    is PeerCertPosition.Leaf -> 0
                    is PeerCertPosition.InChain -> position.index
                }
            if (index !in certificates.indices) return 0
            val view = certificates[index].slice()
            val length = view.remaining()
            if (length <= capacity) {
                der.position(0)
                der.write(view)
                der.position(0)
            }
            return length
        }

        /** The certificates' count: none means the peer presented no chain. */
        fun size(): Int = certificates.size

        fun release() {
            for (certificate in certificates) certificate.freeNativeMemory()
        }

        /** A copy of the leaf, the first certificate; the chain holds one. */
        fun leaf(): LeafCert = copiedLeaf(certificates[0])
    }

    /** The chain could not be copied whole, as [gap] says; nothing of it is kept. */
    class Incomplete(
        val gap: ChainGap,
    ) : PeerChain
}

/** Frees what [this] chain holds. */
internal fun PeerChain.free() =
    when (this) {
        is PeerChain.Copied -> release()
        is PeerChain.NotCopied, is PeerChain.Incomplete -> Unit
    }

/**
 * Inside, once, at completion, as [source] says. Each certificate's length is asked with no room at [probe], which quiche
 * then never writes; one past [MAX_CHAIN_LENGTH] is asked for, so a longer chain is seen as such. What was copied is
 * freed unless the whole chain was, whatever ends the copy.
 */
internal fun peerChainOf(
    source: ChainSource,
    q: QuicheConnRef,
    probe: NativeBytes,
): PeerChain =
    when (source) {
        is ChainSource.NotAsked -> PeerChain.NotCopied
        is ChainSource.Copied -> {
            val kept = ArrayList<PlatformBuffer>()
            var chain: PeerChain = PeerChain.Incomplete(ChainGap.Unreadable)
            try {
                chain = copyInto(kept, q, probe, source.factory)
                chain
            } finally {
                if (chain !is PeerChain.Copied) for (certificate in kept) certificate.freeNativeMemory()
            }
        }
    }

private fun copyInto(
    kept: MutableList<PlatformBuffer>,
    q: QuicheConnRef,
    probe: NativeBytes,
    factory: BufferFactory,
): PeerChain {
    for (index in 0..MAX_CHAIN_LENGTH) {
        val length = q.api.connPeerCertChainAt(q.handle, index, probe.address, 0)
        if (length <= 0) break
        if (index == MAX_CHAIN_LENGTH) return PeerChain.Incomplete(ChainGap.TooLong)
        if (length > MAX_CERT_CAPACITY) return PeerChain.Incomplete(ChainGap.TooLarge(length))
        val certificate = factory.allocate(length)
        kept += certificate
        val read =
            when (val at = NativeBytes.addressOf(certificate)) {
                is ReadAddress.NotAddressable -> 0
                is ReadAddress.At -> q.api.connPeerCertChainAt(q.handle, index, at.address, length)
            }
        if (read != length) return PeerChain.Incomplete(ChainGap.Unreadable)
        certificate.position(0)
        certificate.setLimit(length)
    }
    return PeerChain.Copied(kept.toList())
}

/**
 * Inside, once, at completion: a copy of the peer's leaf certificate, from [chain] when it was copied and from
 * quiche otherwise. A peer that presented none, a client of a server here, is [LeafCert.NotPresented].
 */
internal fun peerLeafOf(
    chain: PeerChain,
    q: QuicheConnRef,
    probe: NativeBytes,
    factory: BufferFactory,
): LeafCert {
    when (chain) {
        is PeerChain.Copied -> if (chain.size() > 0) return chain.leaf()
        is PeerChain.NotCopied, is PeerChain.Incomplete -> Unit
    }
    val length = q.api.connPeerCertChainAt(q.handle, 0, probe.address, 0)
    if (length <= 0) return LeafCert.NotPresented
    val der = factory.allocate(length)
    try {
        val read =
            when (val at = NativeBytes.addressOf(der)) {
                is ReadAddress.NotAddressable -> 0
                is ReadAddress.At -> q.api.connPeerCertChainAt(q.handle, 0, at.address, length)
            }
        if (read != length) return LeafCert.NotPresented
        der.position(0)
        der.setLimit(length)
        return copiedLeaf(der)
    } finally {
        der.freeNativeMemory()
    }
}
