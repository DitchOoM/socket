@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.ServerCertificateRejection
import com.ditchoom.socket.quic.MAX_CERT_CAPACITY
import com.ditchoom.socket.quic.MAX_CHAIN_LENGTH
import com.ditchoom.socket.quic.PeerCertPosition
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.conn.ChainGap
import com.ditchoom.socket.quic.core.conn.PeerChain
import com.ditchoom.socket.quic.core.conn.free
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where a connection's server certificates go once its handshake completed: a client keeps them for its check. */
internal sealed interface ChainHome {
    /** A server's: it checks no certificate of its client. */
    data object NotKept : ChainHome

    class Kept(
        val chain: KeptChain,
    ) : ChainHome
}

/** Where a client's copy of its server's chain stands: not delivered yet, kept, or let go (freed, or freed on arrival). */
private sealed interface ChainHold {
    data object Waiting : ChainHold

    class Kept(
        val chain: PeerChain,
    ) : ChainHold

    data object LetGo : ChainHold
}

/**
 * A client's server certificates, as its completed handshake copied them, kept for the connect that checks the server,
 * which lets them go once done, the connection ended or not. A chain that arrives after it was let go is freed at once.
 * The loop keeps it; the connect alone reads it and lets it go, after its reads.
 */
@Published
internal class KeptChain {
    private val hold = AtomicReference<ChainHold>(ChainHold.Waiting)

    /** Inside, once: the handshake completed with [chain]. */
    fun keep(chain: PeerChain) {
        if (!hold.compareAndSet(ChainHold.Waiting, ChainHold.Kept(chain))) chain.free()
    }

    /**
     * The certificate at [position], with quiche's snprintf contract. A chain that is not kept whole (none delivered,
     * none presented, cut short, or let go) raises the rejection a check of it would come to, so no check passes
     * without the certificates it reads.
     */
    fun copy(
        position: PeerCertPosition,
        der: PlatformBuffer,
        capacity: Int,
    ): Int =
        when (val now = hold.load()) {
            is ChainHold.Kept ->
                when (val chain = now.chain) {
                    is PeerChain.Copied -> if (chain.size() > 0) chain.copy(position, der, capacity) else refused(NO_CHAIN)
                    is PeerChain.Incomplete -> refused(rejectionOf(chain.gap))
                    is PeerChain.NotCopied -> refused(NO_CHAIN)
                }
            is ChainHold.Waiting, is ChainHold.LetGo -> refused(NO_CHAIN)
        }

    /** The connect is done with the chain: it is freed, now or when it arrives. */
    fun letGo() {
        when (val was = hold.exchange(ChainHold.LetGo)) {
            is ChainHold.Kept -> was.chain.free()
            is ChainHold.Waiting, is ChainHold.LetGo -> Unit
        }
    }

    private fun refused(rejection: ServerCertificateRejection): Nothing =
        raise(Raised.ChainRefused(rejection), QuicCloseCircumstance.HandshakeFailed)

    private fun rejectionOf(gap: ChainGap): ServerCertificateRejection =
        when (gap) {
            is ChainGap.TooLong -> ServerCertificateRejection.ChainTooLong(MAX_CHAIN_LENGTH)
            is ChainGap.TooLarge -> ServerCertificateRejection.CertificateTooLarge(gap.length, MAX_CERT_CAPACITY)
            is ChainGap.Unreadable -> NO_CHAIN
        }

    private companion object {
        val NO_CHAIN = ServerCertificateRejection.NoPeerCertificate
    }
}
