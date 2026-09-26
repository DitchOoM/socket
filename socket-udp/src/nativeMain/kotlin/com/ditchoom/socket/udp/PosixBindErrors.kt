package com.ditchoom.socket.udp

import platform.posix.EACCES
import platform.posix.EADDRINUSE
import platform.posix.EADDRNOTAVAIL
import platform.posix.EMFILE
import platform.posix.ENFILE
import platform.posix.ENOBUFS
import platform.posix.ENOMEM
import platform.posix.EPERM
import platform.posix.errno

/**
 * Map the `errno` of a failed `socket(2)`, `bind(2)` or `getsockname(2)` on the way to a bound UDP
 * socket onto [UdpBindError] — the bind-side twin of [connectErrnoToError]. Called with the errno still
 * in hand: before any `close(2)` that could overwrite it.
 */
internal fun bindErrnoToError(code: Int = errno): UdpBindError =
    when (code) {
        EADDRINUSE -> UdpBindError.AddressInUse(code)
        EADDRNOTAVAIL -> UdpBindError.LocalAddressUnavailable(code)
        EACCES, EPERM -> UdpBindError.NotPermitted(code)
        EMFILE, ENFILE, ENOBUFS, ENOMEM -> UdpBindError.SocketUnavailable(code)
        else -> UdpBindError.OsError(code)
    }
