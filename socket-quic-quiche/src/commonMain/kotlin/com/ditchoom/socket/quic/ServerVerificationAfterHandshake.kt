@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import kotlin.time.Clock

/** Where this platform checks a run-to-completion client's server chain against a trust store, for [options]. */
internal expect fun platformChainTrust(options: QuicOptions): PlatformChainTrust

/** This platform's reader of the W3C `serverCertificateHashes` fields of a leaf whose hash already matched a pin. */
internal expect fun parsePinnedLeafFieldsHere(der: ReadBuffer): X509PinFields?

/**
 * Checks the server once the handshake completed and before the connection is handed over, with the 4.x engine's
 * semantics: the `serverCertificateHashes` leaf pin and its W3C constraints, then the platform store where it checks
 * after the handshake ([platformChainTrust]), then each `ServerCertVerifier`, on the caller's context.
 *
 * [readPeerCert] has quiche's snprintf contract for one certificate of the chain. A refused server is closed through
 * [closeWith] with the TLS alert the refusal calls for, then the typed failure is thrown: a
 * [CertificateHashPinningException] or a [ServerCertificateRejectedException].
 */
internal suspend fun verifyServerAfterHandshake(
    serverName: String,
    options: QuicOptions,
    resumption: QuicResumptionOutcome,
    bufferFactory: BufferFactory,
    budget: HandshakeBudget,
    readPeerCert: suspend (position: PeerCertPosition, der: PlatformBuffer, capacity: Int) -> Int,
    closeWith: (QuicError) -> Unit,
) {
    try {
        verifyServerCertificateHashes(
            options.serverCertificateHashes,
            bufferFactory,
            readPeerCertDer = { der, capacity -> readPeerCert(PeerCertPosition.Leaf, der, capacity) },
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
            readChainDer = { index, der, capacity -> readPeerCert(PeerCertPosition.InChain(index), der, capacity) },
            closeConnection = CLOSED_BELOW,
            now = Clock.System.now(),
            budget = budget,
        )
    } catch (refused: Throwable) {
        closeWith(closingErrorOf(refused))
        throw refused
    }
}

/** The verifications' own close is not used: [verifyServerAfterHandshake] closes with the alert the failure calls for. */
private val CLOSED_BELOW: suspend () -> Unit = {}

/** What a refused server's CONNECTION_CLOSE carries: the TLS alert for a certificate refusal, no error otherwise. */
private fun closingErrorOf(refused: Throwable): QuicError =
    when (refused) {
        is CertificateHashPinningException -> QuicError.CryptoError(TLS_BAD_CERTIFICATE)
        is ServerCertificateRejectedException ->
            when (refused.rejection) {
                is ServerCertificateRejection.UntrustedBySystem -> QuicError.CryptoError(TLS_CERTIFICATE_UNKNOWN)
                is ServerCertificateRejection.NoPeerCertificate,
                is ServerCertificateRejection.CertificateTooLarge,
                is ServerCertificateRejection.ChainTooLong,
                is ServerCertificateRejection.RejectedByVerifier,
                -> QuicError.CryptoError(TLS_BAD_CERTIFICATE)
            }
        // Cancelled, or out of budget while checking: nothing is known to be wrong with the certificate.
        else -> QuicError.NoError
    }

/** TLS `bad_certificate` (RFC 8446 §6.2). */
private const val TLS_BAD_CERTIFICATE = 42

/** TLS `certificate_unknown` (RFC 8446 §6.2). */
private const val TLS_CERTIFICATE_UNKNOWN = 46
