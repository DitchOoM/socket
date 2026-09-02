package com.ditchoom.socket.udp

import kotlinx.coroutines.runBlocking
import platform.posix.EACCES
import platform.posix.EADDRINUSE
import platform.posix.EADDRNOTAVAIL
import platform.posix.EMFILE
import platform.posix.ENETUNREACH
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Linux member of the #534 contract — the backend that used to throw one
 * `IllegalStateException("connect to … failed")` with the errno discarded — plus the errno
 * classification it rests on.
 */
class LinuxUdpConnectErrorTests {
    @Test
    fun aConnectOnAHeldLocalPortReportsAddressInUse() = runBlocking { assertConnectOnAHeldLocalPortReportsAddressInUse() }

    @Test
    fun theErrnoClassifiesOntoTheSealedReasonWithTheNumberKept() {
        assertEquals(UdpConnectError.AddressInUse(EADDRINUSE), connectErrnoToError(EADDRINUSE))
        assertEquals(UdpConnectError.LocalAddressUnavailable(EADDRNOTAVAIL), connectErrnoToError(EADDRNOTAVAIL))
        assertEquals(UdpConnectError.Unreachable(ENETUNREACH), connectErrnoToError(ENETUNREACH))
        assertEquals(UdpConnectError.NotPermitted(EACCES), connectErrnoToError(EACCES))
        assertEquals(UdpConnectError.SocketUnavailable(EMFILE), connectErrnoToError(EMFILE))
        assertEquals(UdpConnectError.OsError(EPIPE), connectErrnoToError(EPIPE))
    }
}
