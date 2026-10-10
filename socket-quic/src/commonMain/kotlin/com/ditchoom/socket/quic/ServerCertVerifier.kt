@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.ServerCertificateRejectedException
import kotlin.time.Instant

/**
 * A client's own check of the server's certificate chain, run once the handshake completes and before the
 * connection is handed to the caller. Set through [QuicOptions.serverCertVerifiers].
 *
 * Additive: with [QuicOptions.verifyPeer] on, the platform validates the chain first and a verifier only
 * sees a chain it accepted, so a verifier can narrow trust (pin a key, require an intermediate) but never
 * widen it. With [QuicOptions.verifyPeer] off, the verifiers are the only check.
 *
 * A rejection throws [ServerCertificateRejectedException]. A resumed connection presents no certificate,
 * so verifiers are not consulted for it.
 */
fun interface ServerCertVerifier {
    suspend fun verify(chain: PeerCertificateChain): ServerCertVerdict
}

/**
 * The server's [certificates] as DER, leaf first, in the order it sent them (RFC 8446 §4.4.2). They are
 * read-only views valid only during [ServerCertVerifier.verify]; copy what must outlive it.
 */
class PeerCertificateChain(
    /** The name the connection asked for (TLS SNI): what the leaf has to be valid for. */
    val serverName: String,
    val certificates: List<ReadBuffer>,
    /** The connection's notion of now, for validity-window checks. */
    val now: Instant,
) {
    /** The server's own certificate. */
    val leaf: ReadBuffer get() = certificates.first()
}

/** A [ServerCertVerifier]'s answer. */
sealed interface ServerCertVerdict {
    data object Trusted : ServerCertVerdict

    /** Not trusted, for [reason], which the connect's [ServerCertificateRejectedException] carries. */
    data class Rejected(
        val reason: String,
    ) : ServerCertVerdict
}

/**
 * Where an iOS, tvOS or watchOS client gets its trust anchors when [QuicOptions.verifyPeer] is on and
 * [QuicOptions.trustedCaCertificatesPem] pins none. Other platforms ignore it.
 */
sealed interface AppleTrustSource {
    /**
     * The device's trust store via `SecTrustEvaluateWithError` with an SSL policy for the server name:
     * MDM and user roots, OS revocation and certificate transparency apply. The default.
     */
    data object SystemTrustStore : AppleTrustSource

    /**
     * The Mozilla root bundle compiled into this library, loaded into BoringSSL. Ignores MDM and user roots
     * and OS revocation; as fresh as the library build.
     */
    data object BundledMozillaRoots : AppleTrustSource
}
