package com.ditchoom.socket.quic

/** TLS configuration for a QUIC server. */
data class QuicTlsConfig(
    /** Path to PEM-encoded certificate chain file. */
    val certChainPath: String,
    /** Path to PEM-encoded private key file. */
    val privKeyPath: String,
    /**
     * Read by no server. Every QUIC server, Apple included, loads the PEM [certChainPath] and
     * [privKeyPath].
     */
    val pkcs12Path: String? = null,
    /** Read by no server — see [pkcs12Path]. */
    val pkcs12Password: String? = null,
)
