package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.quic.NwChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Network.framework's own word that the user withholds an access settles a connect as that denial, at once. */
class NwLifeWithheldTests {
    private fun refusedBy(
        pathStatus: Int,
        unsatisfied: Int,
    ): NwVerdict.Refused {
        val settled = assertIs<NwSettled.First>(NwLife().changed(NwChange(WAITING, 0, 0, pathStatus, unsatisfied)))
        return assertIs<NwVerdict.Refused>(settled.verdict)
    }

    @Test
    fun aWaitingConnectionWhosePathIsUnsatisfiedForTheLocalNetworkIsThatDenial() {
        val withheld = assertIs<NwWithheld.Access>(refusedBy(UNSATISFIED, LOCAL_NETWORK_DENIED).withheld)
        assertEquals(NetworkAccess.LocalNetwork, withheld.access)
    }

    @Test
    fun cellularAndWifiTurnedOffForTheAppAreDenialsOfTheirOwn() {
        assertEquals(NetworkAccess.CellularData, assertIs<NwWithheld.Access>(refusedBy(UNSATISFIED, 1).withheld).access)
        assertEquals(NetworkAccess.WifiData, assertIs<NwWithheld.Access>(refusedBy(UNSATISFIED, 2).withheld).access)
    }

    @Test
    fun anInactiveRequiredVpnOrNoReasonIsAMissingRouteNotADenial() {
        assertIs<NwWithheld.None>(refusedBy(UNSATISFIED, 4).withheld)
        assertIs<NwWithheld.None>(refusedBy(UNSATISFIED, 0).withheld)
    }

    @Test
    fun aWaitingConnectionWhosePathCanStillBeSatisfiedKeepsWaiting() {
        assertIs<NwSettled.Not>(NwLife().changed(NwChange(WAITING, 0, 0, SATISFIABLE, LOCAL_NETWORK_DENIED)))
    }

    private companion object {
        const val WAITING = 1
        const val UNSATISFIED = 2
        const val SATISFIABLE = 3
        const val LOCAL_NETWORK_DENIED = 3
    }
}
