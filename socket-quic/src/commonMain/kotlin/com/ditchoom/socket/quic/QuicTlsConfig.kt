package com.ditchoom.socket.quic

/** TLS configuration for a QUIC server. */
data class QuicTlsConfig(
    /** Path to PEM-encoded certificate chain file. */
    val certChainPath: String,
    /** Path to PEM-encoded private key file. */
    val privKeyPath: String,
    /**
     * Path to a PKCS#12 (`.p12`/`.pfx`) bundle of the same cert chain + private key.
     *
     * Apple-only: Network.framework's QUIC listener needs a `sec_identity_t`
     * (a `SecIdentityRef`), and there is no public API to build one from loose
     * PEM cert+key without a keychain or a PKCS#12 blob. The Apple [withQuicServer]
     * imports this via `SecPKCS12Import`. The JVM/Linux/JS servers read the PEM
     * paths above and ignore these two fields, so the field is optional.
     *
     * **Use a small EC (ECDSA P-256) leaf with a minimal/empty chain for an Apple QUIC server.**
     * Network.framework under-counts the client's Initial for RFC 9000 §8.1 anti-amplification, so a
     * large (e.g. RSA-2048 or chained) certificate makes the handshake deadlock against non-Apple
     * clients (quiche/Chrome). See the limitation note on the Apple `withQuicServer` /
     * `buildAppleQuicServer`. Non-blocking for JVM/Linux/JS servers and Apple-to-Apple.
     */
    val pkcs12Path: String? = null,
    /** Passphrase for [pkcs12Path]. Required when [pkcs12Path] is set. */
    val pkcs12Password: String? = null,
)
