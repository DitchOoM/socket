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
            nwConnectVerdict(
                NW_STATE_WAITING,
                errorDomain = 0,
                errorCode = 0,
                pathStatus = NW_PATH_UNSATISFIED,
                unsatisfiedReason = NW_UNSATISFIED_NO_REASON,
            ),
        )
    }

    @Test
    fun aWaitWithANoRouteErrnoFailsAsUnreachableWithThatErrno() {
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.Unreachable(ENETUNREACH)),
            nwConnectVerdict(NW_STATE_WAITING, posix, ENETUNREACH, NW_PATH_SATISFIABLE, NW_UNSATISFIED_NO_REASON),
        )
    }

    @Test
    fun aWaitThatAPathCouldStillSatisfyKeepsWaiting() {
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, 0, 0, NW_PATH_SATISFIABLE, NW_UNSATISFIED_NO_REASON))
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, 0, 0, NW_PATH_SATISFIED, NW_UNSATISFIED_NO_REASON))
        assertEquals(
            NwConnectVerdict.Pending,
            nwConnectVerdict(NW_STATE_WAITING, posix, ETIMEDOUT, NW_PATH_SATISFIABLE, NW_UNSATISFIED_NO_REASON),
        )
        assertEquals(NwConnectVerdict.Pending, nwConnectVerdict(NW_STATE_WAITING, 0, 0, pathStatus = 0, NW_UNSATISFIED_NO_REASON))
    }

    @Test
    fun readyFailedAndCancelledKeepTheirMeaning() {
        assertEquals(NwConnectVerdict.Ready, nwConnectVerdict(3, 0, 0, NW_PATH_SATISFIED, NW_UNSATISFIED_NO_REASON))
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.PlatformError(posix, ENETUNREACH)),
            nwConnectVerdict(4, posix, ENETUNREACH, NW_PATH_UNSATISFIED, NW_UNSATISFIED_NO_REASON),
        )
        assertEquals(NwConnectVerdict.Failed(UdpConnectError.PlatformError(0, 0)), nwConnectVerdict(5, 0, 0, 0, NW_UNSATISFIED_NO_REASON))
    }

    /**
     * An app the user has not allowed onto the local network gets the same unsatisfied path as one with
     * no route at all — on the 11-01 phone-to-phone walk the iPhone reported "no route" to a phone on its
     * own Wi-Fi. The path's reason tells them apart, and only the user can act on it.
     */
    @Test
    fun aPathTheUsersSettingsDenyFailsAsDeniedNotUnreachable() {
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.Denied(DeniedAccess.LocalNetwork)),
            nwConnectVerdict(NW_STATE_WAITING, 0, 0, NW_PATH_UNSATISFIED, NW_UNSATISFIED_LOCAL_NETWORK_DENIED),
        )
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.Denied(DeniedAccess.CellularData)),
            nwConnectVerdict(NW_STATE_WAITING, posix, ENETUNREACH, NW_PATH_UNSATISFIED, NW_UNSATISFIED_CELLULAR_DENIED),
        )
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.Denied(DeniedAccess.WifiData)),
            nwConnectVerdict(NW_STATE_WAITING, 0, 0, NW_PATH_UNSATISFIED, NW_UNSATISFIED_WIFI_DENIED),
        )
        // A required VPN that is down is a missing route, not a switch the user turned.
        assertEquals(
            NwConnectVerdict.Failed(UdpConnectError.Unreachable(ERRNO_NOT_SURFACED)),
            nwConnectVerdict(NW_STATE_WAITING, 0, 0, NW_PATH_UNSATISFIED, unsatisfiedReason = 4),
        )
    }
}
