package com.ditchoom.socket

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
 * ├── SocketConnectionException          — failed to establish connection
 * │   ├── ConnectionRefusedException     — ECONNREFUSED
 * │   ├── NetworkUnreachableException    — ENETUNREACH
 * │   └── HostUnreachableException       — EHOSTUNREACH
 * ├── SocketUnknownHostException         — DNS resolution failed
 * ├── SocketTimeoutException             — connect / read / write timeout
 * ├── SocketIOException                  — generic I/O error (catch-all)
 * └── sealed SSLSocketException          — TLS/SSL errors
 *     ├── SSLHandshakeFailedException    — certificate / handshake failure
 *     └── SSLProtocolException           — other TLS protocol errors
 * ```
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
 * Use the sealed subtypes for finer discrimination.
 */
sealed class SocketClosedException(
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
) : SocketException(message, cause) {
    /** The remote host actively refused the connection (ECONNREFUSED). */
    class Refused(
        val host: String?,
        val port: Int,
        cause: Throwable? = null,
        val platformError: String? = null,
    ) : SocketConnectionException(
            "Connection refused: $host:$port${if (platformError != null) " ($platformError)" else ""}",
            cause,
        )

    /** No route to the destination network (ENETUNREACH). */
    class NetworkUnreachable(
        message: String,
        cause: Throwable? = null,
    ) : SocketConnectionException(message, cause)

    /** No route to the destination host (EHOSTUNREACH). */
    class HostUnreachable(
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
    )

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
) : SocketException(message, cause)

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
) : SocketException(message, cause)

/**
 * The TLS handshake failed (certificate validation, protocol mismatch, etc.).
 */
class SSLHandshakeFailedException(
    message: String,
    cause: Throwable? = null,
) : SSLSocketException(message, cause) {
    constructor(source: Exception) : this(source.message ?: "Failed to complete SSL handshake", source)
}

/**
 * A TLS/SSL protocol error that is not a handshake failure.
 */
class SSLProtocolException(
    message: String,
    cause: Throwable? = null,
) : SSLSocketException(message, cause)
