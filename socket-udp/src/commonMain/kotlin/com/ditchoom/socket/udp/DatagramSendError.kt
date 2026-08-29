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
 * surfacing it, retrying within a send budget so that momentary local buffer pressure never reaches
 * a caller as a failure. It reaches one only when a backend gave up waiting, which is a genuine
 * failure to transmit.
 *
 * That absorption originally had a second motive: `QuicheDriver.flushOutgoing` treated *any* send
 * exception as fatal and would end a live QUIC connection over a full send buffer. **That is no
 * longer true** — `flushOutgoing` now branches on a typed `SendOutcome` and a failed send stops the
 * flush without terminating the connection, because RFC 9000 §10 does not list a failed local send
 * among the ways a connection ends. The absorption stays regardless, on its own merits: a discarded
 * zero-length write would count as transmitted and inflate quiche's bytes-in-flight, surfacing later
 * as spurious loss detection.
 *
 * Numeric codes are kept in their own namespaces on purpose. An `errno` and a Network.framework
 * `(domain, code)` are not the same kind of number, so flattening them into one field would make the
 * value lie about what it means.
 *
 * ## Where each member is constructed
 *
 * A member a consumer can branch on is only worth branching on if every backend constructs it for
 * the same condition. This is the table; a backend that cannot produce a member says so here rather
 * than leaving a consumer to discover it on one platform (#457 was exactly that: `Unreachable` was
 * unconstructible on JVM/Android, so a migration trigger wired to it could never fire there).
 *
 * | Member | K/N POSIX (`sendErrnoToError`) | Apple NW, POSIX domain | JVM / Android NIO (`jvmSendErrorOf`) | Node (`nodeSendError`) |
 * |---|---|---|---|---|
 * | [TooLarge] | parity guard; `EMSGSIZE` | parity guard; `EMSGSIZE` | parity guard; `"Message too long"` | parity guard; `EMSGSIZE` |
 * | [Unreachable] | `EHOSTUNREACH` `ENETUNREACH` `ENETDOWN` `EHOSTDOWN` `EAFNOSUPPORT` | same | `NoRouteToHostException`; the same five errnos' `strerror` text | same five names |
 * | [PortUnreachable] | `ECONNREFUSED` | `ECONNREFUSED` | `PortUnreachableException` | `ECONNREFUSED` |
 * | [NotPermitted] | `EACCES` | `EACCES` | `"Permission denied"` | `EACCES` |
 * | [WouldBlock] | `EAGAIN` `EWOULDBLOCK` `ENOBUFS` | same | send budget exhausted; `"No buffer space available"` | `EAGAIN` `ENOBUFS` |
 * | [OsError] | every other errno | every other errno | **never** — NIO surfaces no errno; [Transport] is its raw member | **never** |
 * | [PlatformError] | never | non-POSIX domains (dns, tls) | never | never |
 * | [Transport] | never | never | every other `IOException`, kept as the cause | every other error, kept as the cause |
 */
sealed interface DatagramSendError {
    /** The payload exceeded what this socket can transmit in one datagram (`EMSGSIZE`). */
    data class TooLarge(
        val attempted: Int,
        val limit: Int,
    ) : DatagramSendError

    /**
     * This socket cannot get a datagram onto a path to the destination: `EHOSTUNREACH`, `ENETUNREACH`,
     * `ENETDOWN`, `EHOSTDOWN`, `EAFNOSUPPORT`.
     *
     * A *local* verdict — no route, the interface is gone, the next hop is dead — which is the signal
     * a migration trigger or an ICE agent branches on. Distinct from [PortUnreachable], where a path
     * exists and the peer host answered on it.
     *
     * [errno] is the platform's code where the runtime surfaces one (the POSIX backends, and Apple's
     * Network.framework POSIX domain) and [ERRNO_NOT_SURFACED] where it does not: JVM/Android NIO
     * reduces the errno to an exception type or its `strerror` text before this library sees it, and
     * Node reports a name. The member is the contract; the number is a diagnostic for the backends
     * that have one.
     */
    data class Unreachable(
        val errno: Int,
    ) : DatagramSendError

    /**
     * The peer host answered an earlier datagram to this destination with ICMP port unreachable — a
     * path exists and something at the far end said nothing listens there (`ECONNREFUSED` on a
     * connected socket; the JVM's `PortUnreachableException`).
     *
     * Its own member, not a case of [Unreachable], because a consumer acts on them oppositely: an ICE
     * agent fails the candidate pair either way, but a QUIC connection must **not** migrate on it —
     * the local path is fine, the peer is not there, and no other path would change that.
     *
     * Only a *connected* socket learns of it: the kernel attributes the ICMP reply to the socket by
     * the 4-tuple, which an unconnected socket does not have. On such a socket the JDK swallows the
     * exception and the send reports success, exactly as the OS does for a bare `sendto`.
     */
    data object PortUnreachable : DatagramSendError

    /**
     * Refused by policy — e.g. a broadcast destination without `SO_BROADCAST` (`EACCES`). [errno] is
     * [ERRNO_NOT_SURFACED] on the backends that do not expose one; see [Unreachable].
     */
    data class NotPermitted(
        val errno: Int,
    ) : DatagramSendError

    /** The socket could not accept the datagram before the backend stopped waiting. Transient. */
    data object WouldBlock : DatagramSendError

    /**
     * Any other POSIX failure, carrying the raw `errno` rather than a rendered message. Never
     * constructed on JVM/Android or Node, which have no errno to carry — their raw member is
     * [Transport].
     */
    data class OsError(
        val errno: Int,
    ) : DatagramSendError

    /** A failure reported in a platform's own namespace: Network.framework's `(domain, code)`. */
    data class PlatformError(
        val domain: Int,
        val code: Int,
    ) : DatagramSendError

    /**
     * The underlying transport threw something this library does not classify, and the exception owns
     * the detail (a JVM `IOException`, a Node `Error`). On JVM/Android this includes a close racing the
     * send (`ClosedChannelException` and its subtypes) — the JDK's own type is the detail there.
     */
    data class Transport(
        val cause: Throwable,
    ) : DatagramSendError

    /** Human-readable rendering. The structured value stays this sealed type; this is only display. */
    fun describe(): String =
        when (this) {
            is TooLarge -> "payload of $attempted bytes exceeds the $limit byte send limit"
            is Unreachable -> "destination unreachable${errnoSuffix(errno)}"
            is PortUnreachable -> "peer answered ICMP port unreachable"
            is NotPermitted -> "send not permitted${errnoSuffix(errno)}"
            is WouldBlock -> "socket could not accept the datagram before the send deadline"
            is OsError -> "send failed (errno=$errno)"
            is PlatformError -> "send failed (domain=$domain, code=$code)"
            is Transport -> "send failed: $cause"
        }
}

/**
 * The `errno` a backend reports when its runtime never surfaced one (JVM/Android NIO, Node). Zero is
 * not an errno on any platform — POSIX errno values start at 1 — so it cannot be mistaken for a code.
 */
internal const val ERRNO_NOT_SURFACED = 0

private fun errnoSuffix(errno: Int): String = if (errno == ERRNO_NOT_SURFACED) "" else " (errno=$errno)"

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
