@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import platform.posix.EACCES
import platform.posix.EAFNOSUPPORT
import platform.posix.EAGAIN
import platform.posix.ECONNREFUSED
import platform.posix.EHOSTDOWN
import platform.posix.EHOSTUNREACH
import platform.posix.EMSGSIZE
import platform.posix.ENETDOWN
import platform.posix.ENETUNREACH
import platform.posix.ENOBUFS
import platform.posix.EWOULDBLOCK
import platform.posix.errno

/**
 * Maps the `errno` left by a failed `sendto`/`sendmsg` onto the typed [DatagramSendError] set.
 *
 * Read `errno` immediately after the failing call — any intervening libc call may overwrite it.
 * [attempted] and [limit] are only consulted for `EMSGSIZE`, where the sizes are the actionable part.
 *
 * This is the reference table the other backends keep parity with — `jvmSendErrorOf` from the JDK's
 * exception types and `strerror` phrases, `nodeSendError` from Node's errno names — so a condition
 * added here must be added there too, and the construction-site table on [DatagramSendError] updated.
 */
internal fun sendErrnoToError(
    code: Int = errno,
    attempted: Int,
    limit: Int,
): DatagramSendError =
    when (code) {
        EMSGSIZE -> DatagramSendError.TooLarge(attempted, limit)
        // The local side cannot put a datagram on a path: no route, the interface is gone (ENETDOWN is
        // what Darwin raises when the interface that held the route goes away), the next hop is dead.
        EHOSTUNREACH, ENETUNREACH, ENETDOWN, EHOSTDOWN, EAFNOSUPPORT -> DatagramSendError.Unreachable(code)
        // ICMP port unreachable came back for an earlier datagram (connected sockets only). Its own
        // member: a path exists, so a consumer must not treat it as the path failing.
        ECONNREFUSED -> DatagramSendError.PortUnreachable
        EACCES -> DatagramSendError.NotPermitted(code)
        // ENOBUFS is Darwin's "the interface queue is full right now" — transient, same class as
        // EAGAIN, and never a reason to tear a connection down.
        EAGAIN, EWOULDBLOCK, ENOBUFS -> DatagramSendError.WouldBlock
        else -> DatagramSendError.OsError(code)
    }
