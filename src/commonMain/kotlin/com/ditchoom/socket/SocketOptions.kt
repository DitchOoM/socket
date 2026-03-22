package com.ditchoom.socket

/**
 * Configuration for TCP socket connections.
 *
 * Combines TCP tuning options and TLS configuration into a single object.
 * TLS is enabled by setting [tls] to a non-null [TlsConfig].
 *
 * ```kotlin
 * // Plaintext with low latency
 * SocketOptions.LOW_LATENCY
 *
 * // TLS with defaults
 * SocketOptions.tlsDefault()
 *
 * // Custom TLS
 * SocketOptions(tcpNoDelay = true, tls = TlsConfig(allowSelfSigned = true))
 * ```
 */
data class SocketOptions(
    /** Disable Nagle's algorithm for low-latency sends. */
    val tcpNoDelay: Boolean? = null,
    /** Enable SO_REUSEADDR. */
    val reuseAddress: Boolean? = null,
    /** Enable TCP keep-alive. */
    val keepAlive: Boolean? = null,
    /** SO_RCVBUF size in bytes. */
    val receiveBuffer: Int? = null,
    /** SO_SNDBUF size in bytes. */
    val sendBuffer: Int? = null,
    /** TLS configuration. null = plaintext. */
    val tls: TlsConfig? = null,
) {
    companion object {
        /** Good defaults for interactive protocols (WebSocket, MQTT, HTTP). */
        val LOW_LATENCY = SocketOptions(tcpNoDelay = true)

        /** TLS with low latency and default certificate validation. */
        fun tlsDefault() = SocketOptions(tcpNoDelay = true, tls = TlsConfig())

        /** TLS with all validation disabled. For development only. */
        fun tlsInsecure() = SocketOptions(tcpNoDelay = true, tls = TlsConfig.INSECURE)
    }
}
