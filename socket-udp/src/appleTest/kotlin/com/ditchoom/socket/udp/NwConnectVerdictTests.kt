package com.ditchoom.socket.udp

import platform.posix.ENETUNREACH
import platform.posix.ETIMEDOUT
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NW parks a connection with no viable path in `waiting` — an IPv6 peer over IPv4-only Wi-Fi — and a
 * connect that ignored it hung below its caller's timeout (279 s on one 10-26 walk attempt).
 */
class NwConnectVerdictTests {
    private val posix = 1

    @Test
    fun aWaitWithAnUnsatisfiedPathAndNoErrorFailsAsUnreachable() {
        // The shape NW actually reports for a v6 peer on IPv4-only Wi-Fi: no error at all.
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.Unreachable(ERRNO_NOT_SURFACED)),
            nwConnectVerdict(NW_STATE_WAITING, errorDomain = 0, errorCode = 0, pathStatus = NW_PATH_UNSATISFIED),
        )
    }

    @Test
    fun aWaitWithANoRouteErrnoFailsAsUnreachableWithThatErrno() {
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.Unreachable(ENETUNREACH)),
            nwConnectVerdict(NW_STATE_WAITING, posix, ENETUNREACH, NW_PATH_SATISFIABLE),
        )
    }

    @Test
    fun aWaitThatAPathCouldStillSatisfyKeepsWaiting() {
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, 0, 0, NW_PATH_SATISFIABLE))
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, 0, 0, NW_PATH_SATISFIED))
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, posix, ETIMEDOUT, NW_PATH_SATISFIABLE))
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, 0, 0, pathStatus = 0))
    }

    @Test
    fun readyFailedAndCancelledKeepTheirMeaning() {
        assertEquals(NwConnectVerdict.Ready, nwConnectVerdict(3, 0, 0, NW_PATH_SATISFIED))
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.PlatformError(posix, ENETUNREACH)),
            nwConnectVerdict(4, posix, ENETUNREACH, NW_PATH_UNSATISFIED),
        )
        assertEquals(NwConnectVerdict.Failed(UdpConnectError.PlatformError(0, 0)), nwConnectVerdict(5, 0, 0, 0))
    }
}
