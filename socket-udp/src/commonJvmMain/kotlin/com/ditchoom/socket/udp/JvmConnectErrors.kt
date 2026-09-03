package com.ditchoom.socket.udp

import java.io.IOException
import java.net.BindException
import java.net.NoRouteToHostException

/**
 * Classify a `java.net` failure raised on the way to a connected UDP socket — `open`, `bind` or
 * `connect` — onto [UdpConnectError] (#534). The connect-side twin of [jvmSendErrorOf].
 *
 * The JDK reduces the errno to a type where it has one and to `strerror` text where it does not
 * (`Net.c handleSocketErrorWithMessage`): `EADDRINUSE` → [BindException] from **both** `bind0` and
 * `connect0` (a 4-tuple collision on connect is reported as a bind failure), `EHOSTUNREACH` →
 * [NoRouteToHostException], and everything else a `SocketException` carrying the phrase. The
 * phrases are Darwin's, glibc's and bionic's renderings, the same table the send classifier uses.
 * Anything unrecognised keeps the exception as [UdpConnectError.Transport]'s cause rather than being
 * flattened into a message.
 */
internal fun jvmConnectErrorOf(e: IOException): UdpConnectError =
    when {
        e is BindException -> UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED)
        e is NoRouteToHostException -> UdpConnectError.Unreachable(ERRNO_NOT_SURFACED)
        else -> byConnectStrerrorPhrase(e)
    }

private fun byConnectStrerrorPhrase(e: IOException): UdpConnectError {
    val message = e.message ?: return UdpConnectError.Transport(e)
    return when {
        message.contains("Address already in use") -> UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED)
        message.contains("Cannot assign requested address") -> UdpConnectError.LocalAddressUnavailable(ERRNO_NOT_SURFACED)
        UNREACHABLE_PHRASES.any { message.contains(it) } -> UdpConnectError.Unreachable(ERRNO_NOT_SURFACED)
        message.contains("Permission denied") || message.contains("Operation not permitted") ->
            UdpConnectError.NotPermitted(ERRNO_NOT_SURFACED)
        message.contains("Too many open files") ||
            message.contains("No buffer space available") ||
            message.contains("Cannot allocate memory") -> UdpConnectError.SocketUnavailable(ERRNO_NOT_SURFACED)
        else -> UdpConnectError.Transport(e)
    }
}
