package com.ditchoom.socket

/**
 * The exhaustive, transport- and platform-neutral *cause* of a connection-establishment or I/O
 * failure — the structured discriminator that replaces the free-form `platformError: String`
 * (issue #166).
 *
 * The same underlying condition (connection refused, untrusted certificate, out of memory during the
 * handshake, …) surfaces as a **different native string per platform** — JSSE messages on JVM, POSIX
 * errno text on Linux K/N, Network.framework / `Sec` codes on Apple, JS error `code`s on Node. Making
 * that string the thing callers switch on makes exhaustive, portable error handling impossible and is
 * the direct cause of the 6 skipped Windows JVM mapping tests (see `TODO.md`).
 *
 * Every platform mapper produces one of these instead. It mirrors the [com.ditchoom.socket.quic.QuicError]
 * gold standard: a sealed hierarchy that a `when` discriminates exhaustively, with the raw platform
 * string demoted to non-discriminating diagnostic detail (the exception `message`/`cause`), never the
 * value callers branch on.
 *
 * Attached to the establishment exceptions via the [ConnectionFailure] interface — implemented by
 * [SocketConnectionException], [SocketUnknownHostException], [SocketTimeoutException] and
 * [SSLSocketException] — so `catch (e: SocketException) { (e as? ConnectionFailure)?.reason?.let { … } }`
 * is exhaustive and identical on every platform.
 *
 * Scope: this models *connect/handshake* failures. Post-establishment connection-loss is already an
 * exhaustive typed surface — the [SocketClosedException] subtypes (`ConnectionReset`, `BrokenPipe`,
 * `EndOfStream`, `General`) — so those conditions are deliberately **not** duplicated here.
 */
sealed interface ConnectionFailureReason {
    /** Human-readable one-line summary — for log/exception messages. The sealed value is the API. */
    val description: String

    /** The remote peer actively refused the connection (ECONNREFUSED / equivalent). */
    data object Refused : ConnectionFailureReason {
        override val description get() = "Connection refused by peer"
    }

    /** No route to the destination host (EHOSTUNREACH / equivalent). */
    data object HostUnreachable : ConnectionFailureReason {
        override val description get() = "No route to host"
    }

    /** No route to the destination network (ENETUNREACH / equivalent). */
    data object NetworkUnreachable : ConnectionFailureReason {
        override val description get() = "Network is unreachable"
    }

    /** DNS resolution failed for the hostname (EAI_NONAME / UnknownHostException / equivalent). */
    data object UnknownHost : ConnectionFailureReason {
        override val description get() = "Hostname could not be resolved"
    }

    /** The connect / read / write deadline elapsed (ETIMEDOUT / SocketTimeout / equivalent). */
    data object Timeout : ConnectionFailureReason {
        override val description get() = "Operation timed out"
    }

    /** The peer's certificate failed validation — untrusted, expired, name-mismatch, etc. */
    data object TlsBadCertificate : ConnectionFailureReason {
        override val description get() = "Peer certificate validation failed"
    }

    /** A TLS handshake failure that is not specifically a certificate rejection. */
    data object TlsHandshake : ConnectionFailureReason {
        override val description get() = "TLS handshake failed"
    }

    /** Attempted TLS against a plaintext peer (or vice versa) — protocol/record mismatch. */
    data object TlsProtocolMismatch : ConnectionFailureReason {
        override val description get() = "TLS protocol mismatch (plaintext peer or record error)"
    }

    /** The platform could not allocate memory to establish the connection (ENOMEM / equivalent). */
    data object OutOfMemory : ConnectionFailureReason {
        override val description get() = "Out of memory establishing connection"
    }

    /**
     * A condition this library does not (yet) classify. Preserves the [raw] platform string as
     * **diagnostic detail only** — do not branch on it; it varies per platform and per version. If a
     * new condition needs portable handling, promote it to its own case above and map it in every
     * platform mapper.
     */
    data class Unknown(
        val raw: String,
    ) : ConnectionFailureReason {
        override val description get() = "Unclassified connection failure: $raw"
    }
}

/**
 * Implemented by the establishment/handshake exceptions that carry a structured
 * [ConnectionFailureReason]. Lets a caller recover the portable cause off the catch-all
 * [SocketException] without knowing the concrete subtype:
 *
 * ```kotlin
 * try {
 *     transport.connect(host, port, config)
 * } catch (e: SocketException) {
 *     when ((e as? ConnectionFailure)?.reason) {
 *         ConnectionFailureReason.Refused -> …
 *         ConnectionFailureReason.TlsBadCertificate -> …
 *         else -> …
 *     }
 * }
 * ```
 */
interface ConnectionFailure {
    val reason: ConnectionFailureReason
}
