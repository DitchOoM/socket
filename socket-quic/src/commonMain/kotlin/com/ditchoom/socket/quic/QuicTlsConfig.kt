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
     */
    val pkcs12Path: String? = null,
    /** Passphrase for [pkcs12Path]. Required when [pkcs12Path] is set. */
    val pkcs12Password: String? = null,
)
