package com.ditchoom.socket.quic

/**
 * Which of the peer's certificates a [QuicheCmd.PeerCert] reads: the leaf (`quiche_conn_peer_cert`), or
 * one position in the chain the peer sent (`quiche_conn_peer_cert_chain_at`), where 0 is the leaf.
 */
sealed interface PeerCertPosition {
    data object Leaf : PeerCertPosition

    data class InChain(
        val index: Int,
    ) : PeerCertPosition
}
