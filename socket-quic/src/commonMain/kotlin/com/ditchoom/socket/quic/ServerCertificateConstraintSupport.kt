package com.ditchoom.socket.quic

/**
 * How thoroughly the current platform enforces the W3C `serverCertificateHashes` certificate
 * *constraints* when a leaf-hash pin is supplied via [QuicOptions.serverCertificateHashes].
 *
 * The leaf-hash pin itself (SHA-256 of the leaf DER) is enforced on **every** platform — it is the sole
 * trust check under the default [CertificateHashVerification.HashOnly]. This type describes only whether
 * the *additional* W3C constraints (validity ≤ 14 days, currently within the validity window, ECDSA P-256
 * key) are also checked, so common code can reason — exhaustively, from a single sealed surface — about
 * what each platform actually does. Read the platform value via [serverCertificateConstraintSupport].
 *
 * The constraints require extracting fields from the leaf certificate with a battle-tested native X.509
 * parser (no hand-rolled ASN.1). Where a platform exposes one (`java.security` on JVM/Android, BoringSSL
 * on Linux, Security.framework on macOS, and the browser's own `WebTransport` stack on the web) the
 * constraints are [Enforced]; where it does not (iOS/tvOS/watchOS lack a public cert-validity API short
 * of ASN.1 parsing) the platform falls back to [LeafHashOnly].
 */
sealed interface ServerCertificateConstraintSupport {
    /**
     * The leaf-hash pin **and** the full W3C certificate constraints (validity ≤ 14 days, currently valid,
     * ECDSA P-256) are enforced — native accepts exactly the certificates a browser would.
     */
    data object Enforced : ServerCertificateConstraintSupport

    /**
     * Only the leaf-hash pin is enforced; the W3C certificate constraints are **not** checked on this
     * platform (no native X.509 parser is available without hand-rolled ASN.1). A pinned leaf still has to
     * match by hash, so trust is established by the pin — the platform is merely more permissive than a
     * browser about the leaf's validity period and key type.
     */
    data object LeafHashOnly : ServerCertificateConstraintSupport
}

/**
 * The [ServerCertificateConstraintSupport] level of the QUIC backend on the current platform. Lets common
 * code branch exhaustively on what `serverCertificateHashes` constraint checking actually runs here
 * (e.g. the shared pinning test suite skips the constraint-reject cases on a [ServerCertificateConstraintSupport.LeafHashOnly]
 * platform). It reflects the platform's capability, not any particular connection's configuration.
 */
expect val serverCertificateConstraintSupport: ServerCertificateConstraintSupport
