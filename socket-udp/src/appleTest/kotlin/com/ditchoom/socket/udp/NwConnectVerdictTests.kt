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
    fun aWaitWithNoRouteFailsAsUnreachableLikeAPosixConnect() {
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.Unreachable(ENETUNREACH)),
            nwConnectVerdict(NW_STATE_WAITING, posix, ENETUNREACH),
        )
    }

    @Test
    fun aWaitForAnyOtherReasonKeepsWaiting() {
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, posix, ETIMEDOUT))
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, errorDomain = 2, errorCode = -65554))
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, errorDomain = 0, errorCode = 0))
    }

    @Test
    fun readyFailedAndCancelledKeepTheirMeaning() {
        assertEquals(NwConnectVerdict.Ready, nwConnectVerdict(3, 0, 0))
        assertEquals(NwConnectVerdict.Failed(UdpConnectError.PlatformError(posix, ENETUNREACH)), nwConnectVerdict(4, posix, ENETUNREACH))
        assertEquals(NwConnectVerdict.Failed(UdpConnectError.PlatformError(0, 0)), nwConnectVerdict(5, 0, 0))
    }
}
