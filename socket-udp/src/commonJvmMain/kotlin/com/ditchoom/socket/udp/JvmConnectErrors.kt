package com.ditchoom.socket.udp

import java.io.IOException
import java.net.BindException
import java.net.NoRouteToHostException

/**
 * Classify a `java.net` failure raised on the way to a connected UDP socket — `open`, `bind` or
 * `connect` — onto [UdpConnectError]. The connect-side twin of [jvmSendErrorOf].
 *
 * The JDK reduces the errno to a type where it has one and to `strerror` text where it does not
 * (`Net.c handleSocketErrorWithMessage`): `EADDRINUSE`, `EADDRNOTAVAIL` and `EACCES` → [BindException]
 * from **both** `bind0` and `connect0` (a 4-tuple collision on connect is reported as a bind
 * failure), `EHOSTUNREACH` → [NoRouteToHostException], and everything else a `SocketException`
 * carrying the phrase. Because one type carries three errnos, the phrase decides — Darwin's, glibc's
 * and bionic's renderings, the same table the send classifier uses. Anything unrecognised keeps the
 * exception as [UdpConnectError.Transport]'s cause rather than being flattened into a message.
 */
internal fun jvmConnectErrorOf(e: IOException): UdpConnectError =
    when (e) {
        is NoRouteToHostException -> UdpConnectError.Unreachable(ERRNO_NOT_SURFACED)
        else ->
            when (strerrorOf(e)) {
                Strerror.AddressInUse -> UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED)
                Strerror.AddressUnavailable -> UdpConnectError.LocalAddressUnavailable(ERRNO_NOT_SURFACED)
                Strerror.Unreachable -> UdpConnectError.Unreachable(ERRNO_NOT_SURFACED)
                Strerror.NotPermitted -> UdpConnectError.NotPermitted(ERRNO_NOT_SURFACED)
                Strerror.OutOfSockets -> UdpConnectError.SocketUnavailable(ERRNO_NOT_SURFACED)
                // A BindException whose phrase is none of the above is still, by type, the endpoint
                // being taken: the most common of the three errnos the JDK files under it.
                Strerror.Unrecognised ->
                    if (e is BindException) UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED) else UdpConnectError.Transport(e)
            }
    }

/**
 * Classify a `java.net` failure raised by a bind — `open`, `bind` or `getsockname` — onto
 * [UdpBindError]. The same phrase table as [jvmConnectErrorOf]: the JDK's [BindException] carries
 * `EADDRINUSE`, `EADDRNOTAVAIL` and `EACCES` alike, so its type alone cannot say whether another port
 * would help.
 */
internal fun jvmBindErrorOf(e: IOException): UdpBindError =
    when (strerrorOf(e)) {
        Strerror.AddressInUse -> UdpBindError.AddressInUse(ERRNO_NOT_SURFACED)
        Strerror.AddressUnavailable -> UdpBindError.LocalAddressUnavailable(ERRNO_NOT_SURFACED)
        Strerror.NotPermitted -> UdpBindError.NotPermitted(ERRNO_NOT_SURFACED)
        Strerror.OutOfSockets -> UdpBindError.SocketUnavailable(ERRNO_NOT_SURFACED)
        Strerror.Unreachable, Strerror.Unrecognised -> UdpBindError.Transport(e)
    }

/** The errno a JDK exception's `strerror` phrase names, for the phrases this module classifies. */
private enum class Strerror { AddressInUse, AddressUnavailable, Unreachable, NotPermitted, OutOfSockets, Unrecognised }

private fun strerrorOf(e: IOException): Strerror {
    val message = e.message ?: return Strerror.Unrecognised
    return when {
        message.contains("Address already in use") -> Strerror.AddressInUse
        // EADDRNOTAVAIL: "Can't assign requested address" on Darwin, "Cannot assign …" on glibc/bionic.
        message.contains("assign requested address") -> Strerror.AddressUnavailable
        UNREACHABLE_PHRASES.any { message.contains(it) } -> Strerror.Unreachable
        message.contains("Permission denied") || message.contains("Operation not permitted") -> Strerror.NotPermitted
        message.contains("Too many open files") ||
            message.contains("No buffer space available") ||
            message.contains("Cannot allocate memory") -> Strerror.OutOfSockets
        else -> Strerror.Unrecognised
    }
}
