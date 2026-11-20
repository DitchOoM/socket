package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppleWithheldAccessTests {
    @Test
    fun aPathUnsatisfiedByAUsersSettingNamesTheAccessWithheld() {
        assertEquals(WithheldAccess.Known(NetworkAccess.CellularData), withheldAccess(1))
        assertEquals(WithheldAccess.Known(NetworkAccess.WifiData), withheldAccess(2))
        assertEquals(WithheldAccess.Known(NetworkAccess.LocalNetwork), withheldAccess(3))
    }

    @Test
    fun noReasonAndAnInactiveVpnAreNotAUsersSetting() {
        assertSame(WithheldAccess.None, withheldAccess(0))
        assertSame(WithheldAccess.None, withheldAccess(4))
    }

    @Test
    fun aDeniedLocalNetworkIsATypedConnectFailure() {
        val failure = WithheldAccess.Known(NetworkAccess.LocalNetwork).connectFailure("printer.local")
        assertEquals(ConnectionFailureReason.AccessDenied(NetworkAccess.LocalNetwork), failure.reason)
    }
}
