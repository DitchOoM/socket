package com.ditchoom.socket.quic

/** TLS configuration for a QUIC server. */
data class QuicTlsConfig(
    /** Path to PEM-encoded certificate chain file. */
    val certChainPath: String,
    /** Path to PEM-encoded private key file. */
    val privKeyPath: String,
    @Deprecated(
        "Read by no server: every QUIC server, Apple included, loads the PEM certChainPath and " +
            "privKeyPath. Removed in 5.0.",
        level = DeprecationLevel.WARNING,
    )
    val pkcs12Path: String? = null,
    @Deprecated(
        "Read by no server: every QUIC server, Apple included, loads the PEM certChainPath and " +
            "privKeyPath. Removed in 5.0.",
        level = DeprecationLevel.WARNING,
    )
    val pkcs12Password: String? = null,
)
