package com.ditchoom.socket.quic

/** TLS configuration for a QUIC server. */
data class QuicTlsConfig(
    /** Path to PEM-encoded certificate chain file. */
    val certChainPath: String,
    /** Path to PEM-encoded private key file. */
    val privKeyPath: String,
)
