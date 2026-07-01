package com.ditchoom.socket

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Sealed hierarchy for all socket errors.
 *
 * On JVM/Android, extends `java.io.IOException` via [PlatformIOException] so that
 * `catch (e: IOException)` catches socket errors (matching Java's convention).
 *
 * ```
 * sealed SocketException : PlatformIOException (IOException on JVM, Exception elsewhere)
 * ├── SocketClosedException              — connection is gone
 * │   ├── ConnectionResetException       — peer sent RST (ECONNRESET)
 * │   ├── BrokenPipeException            — wrote to closed peer (EPIPE)
 * │   └── EndOfStreamException           — clean EOF (bytesRead ≤ 0)
 * ├── SocketConnectionException          — failed to establish connection   (ConnectionFailure)
 * │   ├── Refused                        — ECONNREFUSED
 * │   ├── NetworkUnreachable             — ENETUNREACH
 * │   ├── HostUnreachable                — EHOSTUNREACH
 * │   └── Other(reason)                  — any other typed reason (e.g. OutOfMemory)
 * ├── SocketUnknownHostException         — DNS resolution failed            (ConnectionFailure)
 * ├── SocketTimeoutException             — connect / read / write timeout   (ConnectionFailure)
 * ├── SocketIOException                  — generic I/O error (catch-all)
 * └── sealed SSLSocketException          — TLS/SSL errors                   (ConnectionFailure)
 *     ├── SSLHandshakeFailedException    — certificate / handshake failure
 *     └── SSLProtocolException           — other TLS protocol errors
 * ```
 *
 * Establishment/handshake failures additionally implement [ConnectionFailure], exposing an exhaustive,
 * platform-neutral [ConnectionFailureReason] (`reason`) as the portable discriminator (issue #166).
 */
sealed class SocketException(
    override val message: String,
    override val cause: Throwable? = null,
) : PlatformIOException(message, cause)

// ──────────────────────────────────────────────────────────────────────
// Connection closed / lost
// ──────────────────────────────────────────────────────────────────────

/**
 * The connection is gone — closed locally, by the peer, or due to a broken pipe / reset.
 *
 * Catch this type to handle any "connection lost" scenario uniformly.
 * Use the subtypes for finer discrimination.
 *
 * `abstract` rather than `sealed` so protocol layers built on top of this library (e.g. the QUIC
 * module's `QuicCloseException`, which carries a structured `QuicError`) can extend it from another
 * module and still be caught uniformly via `catch (e: SocketClosedException)`. No code relies on
 * exhaustive `when` over the subtypes — classification uses `is` checks plus an `else`.
 */
abstract class SocketClosedException(
    override val message: String,
    override val cause: Throwable? = null,
) : SocketException(message, cause) {
    /** Generic close — reason unknown or not categorized. */
    class General(
        message: String,
        cause: Throwable? = null,
    ) : SocketClosedException(message, cause)

    /** Peer sent RST (ECONNRESET / ECONNABORTED). */
    class ConnectionReset(
        message: String,
        cause: Throwable? = null,
    ) : SocketClosedException(message, cause)

    /** Wrote to a peer that already closed its end (EPIPE). */
    class BrokenPipe(
        message: String,
        cause: Throwable? = null,
    ) : SocketClosedException(message, cause)

    /** Clean EOF — peer closed the connection gracefully (bytesRead ≤ 0). */
    class EndOfStream(
        message: String = "Connection closed by peer",
        cause: Throwable? = null,
    ) : SocketClosedException(message, cause)
}

// ──────────────────────────────────────────────────────────────────────
// Connection establishment failed
// ──────────────────────────────────────────────────────────────────────

/**
 * Failed to establish a connection to the remote host.
 *
 * Catch this type to handle any "could not connect" scenario.
 * Use the sealed subtypes for finer discrimination.
 */
sealed class SocketConnectionException(
    override val message: String,
    override val cause: Throwable? = null,
) : SocketException(message, cause),
    ConnectionFailure {
    /**
     * The remote host actively refused the connection (ECONNREFUSED).
     *
     * [platformError] is retained as **non-discriminating diagnostic detail** only (issue #166) — it is
     * the raw platform string, which varies per platform/version. Branch on [reason], never on it.
     */
    class Refused(
        val host: String?,
        val port: Int,
        cause: Throwable? = null,
        val platformError: String? = null,
    ) : SocketConnectionException(
            "Connection refused: $host:$port${if (platformError != null) " ($platformError)" else ""}",
            cause,
        ) {
        override val reason: ConnectionFailureReason get() = ConnectionFailureReason.Refused
    }

    /** No route to the destination network (ENETUNREACH). */
    class NetworkUnreachable(
        message: String,
        cause: Throwable? = null,
    ) : SocketConnectionException(message, cause) {
        override val reason: ConnectionFailureReason get() = ConnectionFailureReason.NetworkUnreachable
    }

    /** No route to the destination host (EHOSTUNREACH). */
    class HostUnreachable(
        message: String,
        cause: Throwable? = null,
    ) : SocketConnectionException(message, cause) {
        override val reason: ConnectionFailureReason get() = ConnectionFailureReason.HostUnreachable
    }

    /**
     * A connection-establishment failure whose cause is [reason] but which has no dedicated named
     * subtype — e.g. [ConnectionFailureReason.OutOfMemory]. The escape hatch that lets every platform
     * mapper produce any exhaustive [ConnectionFailureReason] (issue #166) while keeping the common
     * failures ([Refused] / [NetworkUnreachable] / [HostUnreachable]) as ergonomic named types.
     */
    class Other(
        override val reason: ConnectionFailureReason,
        message: String,
        cause: Throwable? = null,
    ) : SocketConnectionException(message, cause)
}

// ──────────────────────────────────────────────────────────────────────
// DNS
// ──────────────────────────────────────────────────────────────────────

/**
 * DNS resolution failed for the given hostname.
 */
class SocketUnknownHostException(
    val hostname: String?,
    extraMessage: String = "",
    override val cause: Throwable? = null,
) : SocketException(
        "Failed to get a socket address for hostname: $hostname${if (extraMessage.isNotEmpty()) "\r\n$extraMessage" else ""}",
        cause,
    ),
    ConnectionFailure {
    override val reason: ConnectionFailureReason get() = ConnectionFailureReason.UnknownHost
}

// ──────────────────────────────────────────────────────────────────────
// Timeout
// ──────────────────────────────────────────────────────────────────────

/**
 * A socket operation timed out (connect timeout, read timeout, write timeout).
 */
class SocketTimeoutException(
    override val message: String,
    val host: String? = null,
    val port: Int = -1,
    override val cause: Throwable? = null,
) : SocketException(message, cause),
    ConnectionFailure {
    override val reason: ConnectionFailureReason get() = ConnectionFailureReason.Timeout
}

// ──────────────────────────────────────────────────────────────────────
// Generic I/O
// ──────────────────────────────────────────────────────────────────────

/**
 * A socket I/O error that doesn't fit a more specific category.
 * This is the catch-all for unexpected or uncategorized errors.
 */
class SocketIOException(
    override val message: String,
    override val cause: Throwable? = null,
) : SocketException(message, cause)

// ──────────────────────────────────────────────────────────────────────
// TLS / SSL
// ──────────────────────────────────────────────────────────────────────

/**
 * Base class for TLS/SSL errors.
 */
sealed class SSLSocketException(
    message: String,
    cause: Throwable? = null,
) : SocketException(message, cause),
    ConnectionFailure

/**
 * The TLS handshake failed (certificate validation, protocol mismatch, etc.).
 *
 * [reason] defaults to [ConnectionFailureReason.TlsHandshake]; a mapper that can tell the failure was
 * specifically a certificate rejection passes [ConnectionFailureReason.TlsBadCertificate] (issue #166).
 */
class SSLHandshakeFailedException(
    message: String,
    cause: Throwable? = null,
    override val reason: ConnectionFailureReason = ConnectionFailureReason.TlsHandshake,
) : SSLSocketException(message, cause) {
    constructor(source: Exception) : this(source.message ?: "Failed to complete SSL handshake", source)
}

/**
 * A TLS/SSL protocol error that is not a handshake failure (e.g. TLS attempted against a plaintext peer).
 */
class SSLProtocolException(
    message: String,
    cause: Throwable? = null,
    override val reason: ConnectionFailureReason = ConnectionFailureReason.TlsProtocolMismatch,
) : SSLSocketException(message, cause)

/**
 * Leaf-certificate hash pinning (W3C `serverCertificateHashes`) rejected the peer. Thrown identically by
 * every backend — the quiche targets verify post-handshake, Apple/Network.framework inside the handshake
 * `verify_block` — so callers handle one type with a structured [failure] instead of parsing a message
 * string.
 *
 * A subtype of [SSLSocketException], so it is caught uniformly via `catch (e: SSLSocketException)` /
 * `catch (e: SocketException)` / `catch (e: IOException)` (JVM/Android), while [failure] is a sealed type
 * a `when` discriminates exhaustively — each case carrying its own detail.
 */
class CertificateHashPinningException(
    val failure: CertificateHashPinningFailure,
    cause: Throwable? = null,
) : SSLSocketException(failure.description, cause) {
    override val reason: ConnectionFailureReason get() = ConnectionFailureReason.TlsBadCertificate
}

/**
 * Why [CertificateHashPinningException] rejected the peer. Sealed so each case carries case-specific data
 * and callers can branch exhaustively; new cases extend the hierarchy where they need to.
 */
sealed interface CertificateHashPinningFailure {
    /** Human-readable summary; the exception's `message`. The structured fields are the API surface. */
    val description: String

    /** The peer presented no leaf certificate to verify against the pins. */
    data object NoPeerCertificate : CertificateHashPinningFailure {
        override val description get() = "Peer presented no leaf certificate to match against serverCertificateHashes"
    }

    /**
     * A leaf certificate was presented and hashed, but its digest matched none of the [pinnedCount]
     * pinned hashes. [computedLeafHash] is the algorithm-prefixed hex of the leaf the server actually
     * presented (e.g. `"sha-256:3e7b…"`) — the value to add to `serverCertificateHashes` to accept it.
     */
    data class HashMismatch(
        val pinnedCount: Int,
        val computedLeafHash: String,
    ) : CertificateHashPinningFailure {
        override val description get() =
            "Server leaf certificate ($computedLeafHash) matched none of the $pinnedCount pinned serverCertificateHashes"
    }

    /**
     * The peer's leaf certificate DER ([sizeBytes]) exceeded the maximum the backend will read
     * ([maxBytes]), so it could not be hashed. A fail-closed guard on the quiche backends; the Apple
     * backend does not produce this (Network.framework imposes no such cap).
     */
    data class CertificateTooLarge(
        val sizeBytes: Int,
        val maxBytes: Int,
    ) : CertificateHashPinningFailure {
        override val description get() = "Peer leaf certificate ($sizeBytes bytes) exceeds the $maxBytes-byte limit for hash pinning"
    }

    // --- W3C serverCertificateHashes certificate constraints (checked once the hash matches) ---
    // These mirror what a browser additionally requires of a `serverCertificateHashes` leaf, so native
    // accepts exactly the certificates a browser would.

    /**
     * The pinned leaf's validity period ([validity], notAfter − notBefore) exceeds the W3C
     * `serverCertificateHashes` maximum ([maxValidity], 14 days).
     */
    data class ValidityPeriodTooLong(
        val validity: Duration,
        val maxValidity: Duration,
    ) : CertificateHashPinningFailure {
        override val description get() =
            "Pinned leaf certificate validity ($validity) exceeds the serverCertificateHashes maximum ($maxValidity)"
    }

    /**
     * The current time [now] is outside the pinned leaf's validity window [notBefore]..[notAfter] — the
     * certificate is expired or not yet valid.
     */
    data class NotTemporallyValid(
        val notBefore: Instant,
        val notAfter: Instant,
        val now: Instant,
    ) : CertificateHashPinningFailure {
        override val description get() = "Pinned leaf certificate is not valid at $now (valid $notBefore .. $notAfter)"
    }

    /**
     * The pinned leaf's public key is not the W3C-required ECDSA P-256 (secp256r1). [detail] describes
     * what was found (e.g. the algorithm/curve OID).
     */
    data class UnsupportedPublicKey(
        val detail: String,
    ) : CertificateHashPinningFailure {
        override val description get() = "Pinned leaf certificate public key is not ECDSA P-256: $detail"
    }

    /**
     * The leaf certificate matched a pin but its DER could not be parsed to extract the W3C constraint
     * fields (validity / public key) — so the constraints could not be checked. Should be unreachable
     * (the leaf was already read and hashed), but the verifier fails closed rather than skip the
     * constraints. [detail] describes the parse error.
     */
    data class CertificateParseFailed(
        val detail: String,
    ) : CertificateHashPinningFailure {
        override val description get() = "Pinned leaf certificate matched a pin but could not be parsed to check W3C constraints: $detail"
    }
}
