@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.ditchoom.socket.quic

import kotlin.time.Instant

/** A [PeerCertificate] could not be generated or presented; [failure] says why. */
class PeerCertificateException(
    val failure: PeerCertificateFailure,
    cause: Throwable? = null,
) : Exception(failure.description, cause)

/** Which [P256KeyPair] output was malformed, and what a well-formed one looks like. */
enum class KeyMaterialPart(
    val expected: String,
) {
    PublicKey("65 bytes starting 0x04 (SEC 1 uncompressed point)"),
    PrivateKey("32 bytes (big-endian scalar)"),
    Signature("a DER SEQUENCE of at most $P256_MAX_SIGNATURE_BYTES bytes"),
}

/** Why [PeerCertificateException] was thrown. */
sealed interface PeerCertificateFailure {
    /** Human-readable summary; the exception's `message`. The structured fields are the API surface. */
    val description: String

    /** The platform could not generate a P-256 key pair. [detail] is the platform's own diagnostic. */
    class KeyGenerationFailed(
        val detail: String,
    ) : PeerCertificateFailure {
        override val description get() = "ECDSA P-256 key generation failed: $detail"
    }

    /** The platform could not sign the certificate. [detail] is the platform's own diagnostic. */
    class SigningFailed(
        val detail: String,
    ) : PeerCertificateFailure {
        override val description get() = "ECDSA P-256 signing failed: $detail"
    }

    /** The backend wrote [part] in the wrong shape ([actualBytes] bytes; see [KeyMaterialPart.expected]). */
    class MalformedKeyMaterial(
        val part: KeyMaterialPart,
        val actualBytes: Int,
    ) : PeerCertificateFailure {
        override val description get() = "Backend wrote a $actualBytes-byte ${part.name}, expected ${part.expected}"
    }

    /** [instant] lies outside the years X.509 can encode (1950 to 9999). */
    class TimeNotEncodable(
        val instant: Instant,
    ) : PeerCertificateFailure {
        override val description get() = "$instant cannot be encoded as an X.509 time (years 1950..9999)"
    }

    /** The certificate was [closed][PeerCertificate.close], so its private key no longer exists. */
    data object Closed : PeerCertificateFailure {
        override val description get() = "PeerCertificate is closed; its private key has been wiped"
    }

    /** The PEM files for the TLS stack could not be written. [detail] is the platform's own diagnostic. */
    class PemFilesFailed(
        val detail: String,
    ) : PeerCertificateFailure {
        override val description get() = "Writing the certificate and key PEM files failed: $detail"
    }
}
