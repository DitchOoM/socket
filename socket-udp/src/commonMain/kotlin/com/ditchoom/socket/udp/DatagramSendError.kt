package com.ditchoom.socket.udp

/**
 * Why a datagram send failed, as a concrete type rather than a parsed message.
 *
 * Modelled as the *reason* rather than as an exception hierarchy so the same values can be carried by
 * a future non-throwing `trySend` outcome without redefining anything: consumers that want to branch
 * (ICE marking a candidate pair unusable) and consumers that want to unwind (quiche tearing the
 * connection down) read the same sealed set.
 *
 * The split that matters is transient vs terminal. [WouldBlock] is the only transient member — the
 * socket could not accept the datagram *yet* — and backends absorb it internally rather than
 * surfacing it, because `QuicheDriver.flushOutgoing` treats any exception from a send as fatal and
 * would tear down a live connection over momentary local buffer pressure. It reaches a caller only
 * when a backend gave up waiting, which is a genuine failure to transmit.
 *
 * Numeric codes are kept in their own namespaces on purpose. An `errno` and a Network.framework
 * `(domain, code)` are not the same kind of number, so flattening them into one field would make the
 * value lie about what it means.
 */
sealed interface DatagramSendError {
    /** The payload exceeded what this socket can transmit in one datagram (`EMSGSIZE`). */
    data class TooLarge(
        val attempted: Int,
        val limit: Int,
    ) : DatagramSendError

    /** No route to the destination from this socket (`EHOSTUNREACH`, `ENETUNREACH`, `EAFNOSUPPORT`). */
    data class Unreachable(
        val errno: Int,
    ) : DatagramSendError

    /** Refused by policy — e.g. a broadcast destination without `SO_BROADCAST` (`EACCES`). */
    data class NotPermitted(
        val errno: Int,
    ) : DatagramSendError

    /** The socket could not accept the datagram before the backend stopped waiting. Transient. */
    data object WouldBlock : DatagramSendError

    /** Any other POSIX failure, carrying the raw `errno` rather than a rendered message. */
    data class OsError(
        val errno: Int,
    ) : DatagramSendError

    /** A failure reported in a platform's own namespace: Network.framework's `(domain, code)`. */
    data class PlatformError(
        val domain: Int,
        val code: Int,
    ) : DatagramSendError

    /** The underlying transport threw and owns the detail (JVM `IOException`, a Node `Error`). */
    data class Transport(
        val cause: Throwable,
    ) : DatagramSendError

    /** Human-readable rendering. The structured value stays this sealed type; this is only display. */
    fun describe(): String =
        when (this) {
            is TooLarge -> "payload of $attempted bytes exceeds the $limit byte send limit"
            is Unreachable -> "destination unreachable (errno=$errno)"
            is NotPermitted -> "send not permitted (errno=$errno)"
            is WouldBlock -> "socket could not accept the datagram before the send deadline"
            is OsError -> "send failed (errno=$errno)"
            is PlatformError -> "send failed (domain=$domain, code=$code)"
            is Transport -> "send failed: $cause"
        }
}

/**
 * Thrown when a send could not transmit. Carries the typed [error]; catch-and-inspect rather than
 * catch-and-parse.
 *
 * This is the reporting half of the module's send contract: **a send either delivers or reports, and
 * never returns normally having sent nothing.** Four of five backends used to discard their send
 * result, so a datagram could vanish between a clean return and the wire — invisible to a caller and,
 * for quiche, a lie to its congestion controller.
 */
class DatagramSendException(
    val error: DatagramSendError,
) : RuntimeException(error.describe(), (error as? DatagramSendError.Transport)?.cause)
