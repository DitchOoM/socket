@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import com.ditchoom.socket.quic.HandshakeBudget
import com.ditchoom.socket.quic.PeerCertPosition
import com.ditchoom.socket.quic.PlatformChainTrust
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConfigCalls
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.X509PinFields
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.verifyServerCertificateChain
import com.ditchoom.socket.quic.verifyServerCertificateHashes
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Clock

/** Where this platform checks a core client's server chain against a trust store, for a connect with [options]. */
internal expect fun platformChainTrust(options: QuicOptions): PlatformChainTrust

/** This platform's reader of the W3C `serverCertificateHashes` fields of a leaf whose hash already matched a pin. */
internal expect fun parsePinnedLeafFieldsHere(der: ReadBuffer): X509PinFields?

/**
 * The calls a client config is built with from [options]: the shared option calls, with quiche's own chain check
 * off where the platform's store checks the chain after the handshake instead.
 */
internal fun applyClientOptions(
    options: QuicOptions,
    calls: QuicConfigCalls,
) {
    applyQuicOptions(options, calls, QuicRole.Client)
    when (platformChainTrust(options)) {
        PlatformChainTrust.InHandshake -> Unit
        is PlatformChainTrust.AfterHandshake -> calls.verifyPeer(false)
    }
}

/**
 * Checks the server once the handshake completed and before the connect returns, with the 4.x engine's semantics:
 * the `serverCertificateHashes` leaf pin and its W3C constraints, then the platform store where it checks after the
 * handshake, then each `ServerCertVerifier`. Runs on the caller's context, so a verifier never runs on the loop. A
 * refused server is told why in its CONNECTION_CLOSE and the caller gets the typed failure; the connection never
 * reaches the caller, so none of its stream data does either.
 */
internal suspend fun CoreConnection.verifyServer(
    serverName: String,
    options: QuicOptions,
    budget: HandshakeBudget,
) {
    try {
        verifyServerCertificateHashes(
            options.serverCertificateHashes,
            bufferFactory,
            readPeerCertDer = { der, capacity -> peerCertificate(PeerCertPosition.Leaf, der, capacity) },
            closeConnection = CLOSED_BELOW,
            parseLeafFields = ::parsePinnedLeafFieldsHere,
            now = Clock.System.now(),
        )
        verifyServerCertificateChain(
            serverName,
            resumption,
            platformChainTrust(options),
            options.serverCertVerifiers,
            bufferFactory,
            readChainDer = { index, der, capacity -> peerCertificate(PeerCertPosition.InChain(index), der, capacity) },
            closeConnection = CLOSED_BELOW,
            now = Clock.System.now(),
            budget = budget,
        )
    } catch (refused: Throwable) {
        closeNow(closingErrorOf(refused))
        throw refused
    }
}

/** The verifications' own close is not used: [verifyServer] closes with the alert its failure calls for. */
private val CLOSED_BELOW: suspend () -> Unit = {}

/** What a refused server's CONNECTION_CLOSE carries: the TLS alert for a certificate refusal, no error otherwise. */
private fun closingErrorOf(refused: Throwable): QuicError =
    when (refused) {
        is CertificateHashPinningException -> QuicError.CryptoError(TLS_BAD_CERTIFICATE)
        is ServerCertificateRejectedException ->
            when (refused.rejection) {
                is ServerCertificateRejection.UntrustedBySystem -> QuicError.CryptoError(TLS_CERTIFICATE_UNKNOWN)
                ServerCertificateRejection.NoPeerCertificate,
                is ServerCertificateRejection.CertificateTooLarge,
                is ServerCertificateRejection.ChainTooLong,
                is ServerCertificateRejection.RejectedByVerifier,
                -> QuicError.CryptoError(TLS_BAD_CERTIFICATE)
            }
        // A connect cancelled or out of budget while checking: nothing is known to be wrong with the certificate.
        else -> QuicError.NoError
    }

/** Reads one certificate of the peer's chain inside the connection. */
internal class PeerCertWork(
    private val position: PeerCertPosition,
    private val address: Long,
    private val capacity: Int,
    private val answer: CompletableDeferred<Int>,
) : ConnWork() {
    override fun run(inside: Inside) {
        val read =
            try {
                inside.peerCertificate(position, address, capacity)
            } catch (failed: Exception) {
                answer.completeExceptionally(failed)
                throw failed
            }
        answer.complete(read)
    }

    override fun gone(end: ConnEnd) {
        answer.completeExceptionally(endedWith(end, QuicCloseCircumstance.HandshakeFailed))
    }
}

/** TLS `bad_certificate` (RFC 8446 §6.2). */
private const val TLS_BAD_CERTIFICATE = 42

/** TLS `certificate_unknown` (RFC 8446 §6.2). */
private const val TLS_CERTIFICATE_UNKNOWN = 46
