package com.ditchoom.socket.udp

import platform.posix.EACCES
import platform.posix.EADDRINUSE
import platform.posix.EADDRNOTAVAIL
import platform.posix.EAFNOSUPPORT
import platform.posix.EHOSTDOWN
import platform.posix.EHOSTUNREACH
import platform.posix.EMFILE
import platform.posix.ENETDOWN
import platform.posix.ENETUNREACH
import platform.posix.ENFILE
import platform.posix.ENOBUFS
import platform.posix.ENOMEM
import platform.posix.EPERM
import platform.posix.errno

/**
 * Map the `errno` of a failed `socket(2)`, `bind(2)` or `connect(2)` on the way to a connected UDP
 * socket onto [UdpConnectError] — the connect-side twin of [sendErrnoToError], sharing its
 * unreachable set so a route that a send would report as gone is the one a connect reports as gone.
 *
 * Called with the errno still in hand (#534): the Linux backend used to discard it in favour of one
 * `IllegalStateException("connect to … failed")`, which is what made a sealed reason unconstructible
 * there. Every member is reachable here, so a consumer's `when` over the type is exhaustive on the
 * POSIX backends without a fallback arm that never fires.
 */
internal fun connectErrnoToError(code: Int = errno): UdpConnectError =
    when (code) {
        EADDRINUSE -> UdpConnectError.AddressInUse(code)
        EADDRNOTAVAIL -> UdpConnectError.LocalAddressUnavailable(code)
        EHOSTUNREACH, ENETUNREACH, ENETDOWN, EHOSTDOWN, EAFNOSUPPORT -> UdpConnectError.Unreachable(code)
        EACCES, EPERM -> UdpConnectError.NotPermitted(code)
        EMFILE, ENFILE, ENOBUFS, ENOMEM -> UdpConnectError.SocketUnavailable(code)
        else -> UdpConnectError.OsError(code)
    }
