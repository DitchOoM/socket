package com.ditchoom.socket.udp

/**
 * Why [UdpSocket.connect] could not produce a connected channel — the socket could not be created,
 * the requested local endpoint could not be bound, or the 4-tuple could not be fixed — as a sealed
 * value carried by [UdpConnectException], on every backend.
 *
 * The connect-side counterpart of [DatagramSendError] (#457), and filed for the same reason (#534):
 * the backends disagreed about how a refused connect was reported. JVM/Android raised the JDK's own
 * `java.net` types, Apple a message-only exception, Node a message-only exception, and Linux one bare
 * `IllegalStateException("connect to … failed")` with the errno discarded — so a caller that needed to
 * tell "no descriptor left" from "that 4-tuple is taken" (the route probe of #434/#523/#547) could
 * not, and a sealed reason for it could not be minted because one backend was structurally unable to
 * construct it.
 *
 * ## Parity across the five backends
 *
 * Every member is reachable from the POSIX backends, which always have an `errno`. Where a backend
 * reduces the errno to a type or a phrase before this library sees it, the member is still the
 * contract and [errno][ERRNO_NOT_SURFACED] is what the number carries:
 *
 * - **Linux (io_uring)**: `socket(2)` / `bind(2)` / `connect(2)` each set `errno`; it is classified
 *   at the call, never discarded.
 * - **JVM / Android**: `BindException` (`EADDRINUSE` from either the bind or the connect — the JDK
 *   raises the same type for both, which is why [AddressInUse] does not say which), the JDK's typed
 *   unreachable exception, and the `strerror` phrases the untyped `SocketException` carries.
 * - **Apple (Network.framework)**: the connection's terminal `failed` state, reported in NW's own
 *   `(domain, code)` namespace as [PlatformError] — a POSIX-domain error carries the errno as its
 *   code — or [SocketUnavailable] when NW would not create the connection at all.
 * - **Node**: the `error` event's `code` name; [Transport] when it is none of the recognised names.
 *
 * [OsError] is never constructed on JVM/Android or Node (they have no errno to carry; their raw
 * member is [Transport]); [PlatformError] only on Apple; [Transport] never on the POSIX backends.
 */
sealed interface UdpConnectError {
    /**
     * The endpoint this socket needed is held by another socket: the requested local port (`bind`), or
     * the 4-tuple the connect would have formed (`connect`, the #434 collision an unnamed bind against
     * a busy peer can draw). `EADDRINUSE`; the JDK's `BindException` for both.
     */
    data class AddressInUse(
        val errno: Int,
    ) : UdpConnectError

    /**
     * The requested local address is not one of this host's (`EADDRNOTAVAIL`) — the interface that
     * carried it is gone, or it never existed here. The address-level sibling of [Unreachable].
     */
    data class LocalAddressUnavailable(
        val errno: Int,
    ) : UdpConnectError

    /**
     * No path from this host to the peer: `EHOSTUNREACH`, `ENETUNREACH`, `ENETDOWN`, `EHOSTDOWN`,
     * `EAFNOSUPPORT` — the same local verdict [DatagramSendError.Unreachable] reports for a send.
     */
    data class Unreachable(
        val errno: Int,
    ) : UdpConnectError

    /** Refused by policy: `EACCES`, `EPERM` (a privileged port, a sandbox, a firewall rule). */
    data class NotPermitted(
        val errno: Int,
    ) : UdpConnectError

    /**
     * The socket itself could not be created: `EMFILE`, `ENFILE`, `ENOBUFS`, `ENOMEM` — the process or
     * the host is out of descriptors or socket memory — or the platform's factory returned nothing.
     * The reason a retry loop must back off rather than try again at once.
     */
    data class SocketUnavailable(
        val errno: Int,
    ) : UdpConnectError

    /**
     * Any other POSIX failure, carrying the raw `errno` rather than a rendered message. Never
     * constructed on JVM/Android or Node, which have no errno to carry — their raw member is
     * [Transport].
     */
    data class OsError(
        val errno: Int,
    ) : UdpConnectError

    /**
     * A failure reported in a platform's own namespace: Network.framework's `(domain, code)`, where
     * domain 1 is POSIX and the code is then the errno.
     */
    data class PlatformError(
        val domain: Int,
        val code: Int,
    ) : UdpConnectError

    /**
     * The underlying transport threw something this library does not classify, and the exception owns
     * the detail (a JVM `IOException`, a Node `Error`).
     */
    data class Transport(
        val cause: Throwable,
    ) : UdpConnectError

    /** Human-readable rendering. The structured value stays this sealed type; this is only display. */
    fun describe(): String =
        when (this) {
            is AddressInUse -> "the local endpoint or 4-tuple is already in use" + errnoSuffix(errno)
            is LocalAddressUnavailable -> "the requested local address is not available on this host" + errnoSuffix(errno)
            is Unreachable -> "no route to the peer from this host" + errnoSuffix(errno)
            is NotPermitted -> "not permitted" + errnoSuffix(errno)
            is SocketUnavailable -> "the socket could not be created" + errnoSuffix(errno)
            is OsError -> "connect failed (errno=$errno)"
            is PlatformError -> "connect failed (domain=$domain, code=$code)"
            is Transport -> "connect failed: ${cause::class.simpleName}: ${cause.message}"
        }

    private fun errnoSuffix(errno: Int): String = if (errno == ERRNO_NOT_SURFACED) "" else " (errno=$errno)"
}

/**
 * Thrown by [UdpSocket.connect] when no connected channel could be produced; [error] says why, as a
 * value a caller can branch on. Public so a consumer in another module can catch it and map it — the
 * QUIC connect ladder and its route probe do.
 */
class UdpConnectException(
    val error: UdpConnectError,
    cause: Throwable? = (error as? UdpConnectError.Transport)?.cause,
) : RuntimeException(error.describe(), cause)
