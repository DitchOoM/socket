@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.ServerCertificateRejectedException
import kotlin.time.Instant

/**
 * A client connection's own say in whether the server it reached is the one it meant: consulted with the
 * server's whole certificate chain once the handshake completes and **before** the connection is handed
 * to the caller, so a rejected server never sees a byte of application data. Set it through
 * [QuicOptions.serverCertVerifier].
 *
 * **Additive, never a bypass.** With [QuicOptions.verifyPeer] on (the default) the platform's own chain
 * validation — BoringSSL against the configured anchors, or the iOS trust store ([AppleTrustSource]) —
 * runs first, and this verifier is consulted only for a chain that passed it. It narrows what is trusted
 * (pin a key, require an intermediate, apply a SAN rule); it cannot widen it. Turning [QuicOptions.verifyPeer]
 * off makes this verifier the only check, which is the caller's explicit choice of a private trust model.
 *
 * Its answer is a [ServerCertVerdict], not a `Boolean`: a rejection carries its reason into the
 * [ServerCertificateRejectedException] the connect throws.
 *
 * A resumed connection presents no certificate — its trust was settled on the connection that issued
 * the ticket — so the verifier is not consulted for one ([QuicResumptionOutcome.Resumed]).
 */
fun interface ServerCertVerifier {
    suspend fun verify(chain: PeerCertificateChain): ServerCertVerdict
}

/**
 * The certificates a server presented, as it presented them.
 *
 * [certificates] are DER, the leaf first and then the intermediates in the order the server sent them
 * (RFC 8446 §4.4.2). They are read-only views valid only for the duration of [ServerCertVerifier.verify];
 * copy what must outlive it.
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
 * Where a client on iOS, tvOS and watchOS gets the anchors its server certificate chains to, when
 * [QuicOptions.verifyPeer] is on and [QuicOptions.trustedCaCertificatesPem] pins none. macOS, Linux, the JVM
 * and Android validate against the platform's store inside BoringSSL and ignore this.
 */
sealed interface AppleTrustSource {
    /**
     * The device's own trust store, through Security.framework (`SecTrustEvaluateWithError` with an SSL
     * policy for the server name): roots installed by MDM or the user, OS revocation and certificate
     * transparency policy all apply, exactly as for the device's own TLS. The default.
     */
    data object SystemTrustStore : AppleTrustSource

    /**
     * The Mozilla root bundle compiled into this library, loaded into BoringSSL. Static: it ignores
     * MDM and user-installed roots and OS revocation, and is only as fresh as the library build. Kept for
     * an app that must behave identically across Apple and non-Apple devices.
     */
    data object BundledMozillaRoots : AppleTrustSource
}
