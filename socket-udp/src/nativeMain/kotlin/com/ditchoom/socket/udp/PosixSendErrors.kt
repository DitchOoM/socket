@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import platform.posix.EACCES
import platform.posix.EAFNOSUPPORT
import platform.posix.EAGAIN
import platform.posix.EHOSTUNREACH
import platform.posix.EMSGSIZE
import platform.posix.ENETUNREACH
import platform.posix.ENOBUFS
import platform.posix.EWOULDBLOCK
import platform.posix.errno

/**
 * Maps the `errno` left by a failed `sendto`/`sendmsg` onto the typed [DatagramSendError] set.
 *
 * Read `errno` immediately after the failing call — any intervening libc call may overwrite it.
 * [attempted] and [limit] are only consulted for `EMSGSIZE`, where the sizes are the actionable part.
 */
internal fun sendErrnoToError(
    code: Int = errno,
    attempted: Int,
    limit: Int,
): DatagramSendError =
    when (code) {
        EMSGSIZE -> DatagramSendError.TooLarge(attempted, limit)
        EHOSTUNREACH, ENETUNREACH, EAFNOSUPPORT -> DatagramSendError.Unreachable(code)
        EACCES -> DatagramSendError.NotPermitted(code)
        // ENOBUFS is Darwin's "the interface queue is full right now" — transient, same class as
        // EAGAIN, and never a reason to tear a connection down.
        EAGAIN, EWOULDBLOCK, ENOBUFS -> DatagramSendError.WouldBlock
        else -> DatagramSendError.OsError(code)
    }
