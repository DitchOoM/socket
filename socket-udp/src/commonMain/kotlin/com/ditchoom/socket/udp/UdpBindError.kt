package com.ditchoom.socket.udp

/**
 * Why [UdpSocket.bind] or [UdpSocket.bindMulticast] could not bind the requested local endpoint, as a
 * sealed value carried by [UdpBindException], on every backend.
 *
 * The bind-side counterpart of [UdpConnectError]. A caller that binds on the kernel's choice of port
 * needs to tell "that port is taken, draw another" ([AddressInUse]) from "that address is not this
 * host's" ([LocalAddressUnavailable]) — one is worth retrying and the other never is — so no backend
 * may report a refused bind as a message-only exception with the errno discarded.
 *
 * Where a backend reduces the errno to a type or a phrase before this library sees it (the JDK,
 * Node), the member is still the contract and [errno][ERRNO_NOT_SURFACED] is what the number carries.
 */
sealed interface UdpBindError {
    /** Another socket holds the requested address and port (`EADDRINUSE`). */
    data class AddressInUse(
        val errno: Int,
    ) : UdpBindError

    /**
     * The requested address is not one this host can bind (`EADDRNOTAVAIL`): the interface that carried
     * it is gone, it never existed here, or it is not yet usable (an IPv6 address still in duplicate
     * address detection).
     */
    data class LocalAddressUnavailable(
        val errno: Int,
    ) : UdpBindError

    /** Refused by policy: `EACCES`, `EPERM` (a privileged port, a sandbox). */
    data class NotPermitted(
        val errno: Int,
    ) : UdpBindError

    /**
     * The socket itself could not be created, or the bound socket could not report its address:
     * `EMFILE`, `ENFILE`, `ENOBUFS`, `ENOMEM`, or a `getsockname` that failed.
     */
    data class SocketUnavailable(
        val errno: Int,
    ) : UdpBindError

    /** Any other POSIX failure, carrying the raw `errno`. Never constructed on JVM/Android or Node. */
    data class OsError(
        val errno: Int,
    ) : UdpBindError

    /** The underlying transport threw something this library does not classify; the exception owns the detail. */
    data class Transport(
        val cause: Throwable,
    ) : UdpBindError

    /** Human-readable rendering. The structured value stays this sealed type; this is only display. */
    fun describe(): String =
        when (this) {
            is AddressInUse -> "the address and port are already in use" + errnoSuffix(errno)
            is LocalAddressUnavailable -> "the address is not available on this host" + errnoSuffix(errno)
            is NotPermitted -> "not permitted" + errnoSuffix(errno)
            is SocketUnavailable -> "the socket could not be created" + errnoSuffix(errno)
            is OsError -> "bind failed (errno=$errno)"
            is Transport -> "bind failed: ${cause::class.simpleName}: ${cause.message}"
        }

    private fun errnoSuffix(errno: Int): String = if (errno == ERRNO_NOT_SURFACED) "" else " (errno=$errno)"
}

/**
 * Thrown by [UdpSocket.bind] and [UdpSocket.bindMulticast] when the local endpoint could not be bound.
 * [host] and [port] are the endpoint that was asked of the kernel — for a wildcard bind, the wildcard
 * literal and the port actually attempted — and [error] says why, as a value a caller can branch on.
 */
class UdpBindException(
    val host: String,
    val port: Int,
    val error: UdpBindError,
    cause: Throwable? = (error as? UdpBindError.Transport)?.cause,
) : RuntimeException("bind to ${bracketed(host)}:$port failed: ${error.describe()}", cause)

private fun bracketed(host: String): String = if (host.contains(':')) "[$host]" else host
